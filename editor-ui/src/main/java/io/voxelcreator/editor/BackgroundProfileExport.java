package io.voxelcreator.editor;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.*;

/** One frozen review/format/path attempt; file replacement follows current Profiler controls. */
final class BackgroundProfileExport implements AutoCloseable {
  private final Executor executor;
  private final Consumer<Runnable> ui;
  private final ProfileExportFiles files;
  private final Supplier<Path> activeProject;
  private final Consumer<String> errors, cleanupErrors;
  private final OwnedPreviewMailbox<Payload> handoff = new OwnedPreviewMailbox<>();
  private FutureTask<Void> task;
  private ProfileExportSession.Attempt attempt;
  private BooleanSupplier gate;
  private Object token = new Object();
  private long version;
  private boolean running, ready, admitted, closed, publishing, closeRequested;
  private String message = "";
  private Path target;

  private final class Payload implements AutoCloseable {
    final ProfileExportFiles.Prepared prepared;
    final Throwable failure;
    Payload(ProfileExportFiles.Prepared prepared, Throwable failure) { this.prepared = prepared; this.failure = failure; }
    public void close() {
      if (prepared == null) return;
      try { prepared.close(); }
      catch (Throwable failure) { report(cleanupErrors, "Unpublished capture cleanup failed: " + BackgroundFailure.describe(failure)); }
    }
  }

  BackgroundProfileExport(Executor executor, Consumer<Runnable> ui, ProfileExportFiles files,
      Supplier<Path> activeProject, Consumer<String> errors, Consumer<String> cleanupErrors) {
    this.executor = Objects.requireNonNull(executor);
    this.ui = Objects.requireNonNull(ui);
    this.files = Objects.requireNonNull(files);
    this.activeProject = Objects.requireNonNull(activeProject);
    this.errors = Objects.requireNonNull(errors);
    this.cleanupErrors = Objects.requireNonNull(cleanupErrors);
  }

  boolean start(ProfileExportSession review, String path, boolean csv, BooleanSupplier current) {
    if (closed || running || publishing) return false;
    Object request = retire();
    if (request != token || closed || running || publishing) return false;
    try {
      target = ProfileExportSession.location(path);
      Path active = activeProject.get();
      if (request != token || closed) return false;
      ExportPaths.rejectProjectTarget(target, active, "capture file");
      gate = Objects.requireNonNull(current);
      attempt = review.reserve();
      var snapshot = review.snapshot(); var destination = target;
      running = true;
      if (!current(request)) { if (owns(request)) cancel(); return false; }
      long delivery = handoff.begin();
      message = "Preparing the reviewed capture in the background. Cancel Export or Escape keeps the review.";
      task = new FutureTask<>(() -> {
        ProfileExportFiles.Prepared prepared = null; Throwable failure = null;
        try { prepared = files.prepare(destination, snapshot, csv, active); }
        catch (Throwable rejected) { failure = rejected; }
        Payload payload;
        try { payload = new Payload(prepared, failure); }
        catch (Throwable allocation) {
          if (prepared != null) try { prepared.close(); }
          catch (Throwable cleanup) { if (allocation != cleanup) allocation.addSuppressed(cleanup); }
          throw allocation;
        }
        if (!handoff.publish(delivery, payload)) return null;
        ui.accept(() -> delivered(request, delivery));
        return null;
      });
      executor.execute(task);
      if (owns(request)) admitted = true;
      return owns(request);
    } catch (Throwable failure) { fail(request, failure); return false; }
  }

  private boolean owns(Object request) { return !closed && running && token == request; }

  private boolean current(Object request) {
    var captured = attempt; var check = gate;
    return captured != null && check != null && check.getAsBoolean()
        && owns(request) && attempt == captured && captured.current();
  }

  private void delivered(Object request, long delivery) {
    if (owns(request) && handoff.current(delivery)) ready = true;
  }

  void refresh() {
    if (!running || publishing) return;
    Object request = token;
    try {
      boolean valid = current(request);
      if (!owns(request)) return;
      if (!valid) {
        Object retired = retire();
        if (token == retired) message = "Export input changed. Review the path and export explicitly again.";
      }
    } catch (Throwable failure) { fail(request, failure); }
  }

  Path accept(long shownVersion) {
    if (shownVersion != version || publishing) return null;
    Object request = token; refresh();
    if (!owns(request) || shownVersion != version) return null;
    var completion = BackgroundFailure.completed(task);
    if (!owns(request) || !admitted || completion == null || !ready && completion.failure() == null) return null;
    Payload payload = null;
    try {
      if (!current(request) || !owns(request)) return null;
      Path active = activeProject.get();
      if (!owns(request) || !current(request)) return null;
      payload = handoff.take();
      Throwable failure = completion.failure() != null ? completion.failure() : payload == null
          ? new IllegalStateException("Capture staging was not delivered") : payload.failure;
      if (failure != null) { fail(request, failure); return null; }
      Path written = target;
      publishing = true;
      attempt.publish(Objects.requireNonNull(payload.prepared), active);
      // Consume before status, native consequences, cleanup feedback or a successor.
      task = null;
      retire();
      message = "Capture exported to " + written;
      return written;
    } catch (Throwable failure) { fail(request, failure); return null; }
    finally {
      publishing = false;
      if (payload != null) payload.close();
      if (closeRequested) close();
    }
  }

  boolean running() { refresh(); return running; }
  boolean ready() { return ready; }
  long version() { return version; }
  String message() { return message; }

  void cancel() {
    if (!running || publishing) return;
    Object retired = retire();
    if (token == retired) message = "Capture export cancelled. The review and previous destination are retained.";
  }

  private Object retire() {
    var previous = task; var reservation = attempt;
    Object retired = new Object();
    token = retired; version++;
    task = null; attempt = null; gate = null; target = null;
    running = ready = admitted = false;
    if (reservation != null) reservation.close();
    handoff.invalidate();
    if (previous != null) {
      previous.cancel(true);
      if (executor instanceof ThreadPoolExecutor pool) pool.remove(previous);
    }
    return retired;
  }

  private void fail(Object request, Throwable failure) {
    if (request != token || closed) return;
    Object retired = retire();
    if (token != retired || closed) return;
    String feedback = StatusBarText.bounded("Export failed: " + BackgroundFailure.describe(failure), 3072);
    if (token != retired || closed) return;
    message = feedback;
    report(errors, message);
  }

  private static void report(Consumer<String> reporter, String detail) {
    try { reporter.accept(detail); } catch (Throwable ignored) { /* Recovery remains available. */ }
  }

  @Override
  public void close() {
    if (closed) return;
    if (publishing) { closeRequested = true; return; }
    closed = true;
    retire(); handoff.close();
  }
}

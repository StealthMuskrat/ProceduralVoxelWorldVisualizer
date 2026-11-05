package io.voxelcreator.core;

import io.voxelcreator.model.Diagnostic;
import java.util.*;

/**
 * A compilation error with the innermost authored resource/control, retaining its original cause.
 * Exception and diagnostic share one safe description bounded to 2,048 UTF-16 units.
 */
public final class CompilationFailure extends IllegalArgumentException {
  private final Diagnostic diagnostic;

  CompilationFailure(UUID resource, UUID item, String parameter, RuntimeException cause) {
    this(resource, item, parameter, cause, FailureText.describe(Objects.requireNonNull(cause)));
  }

  private CompilationFailure(UUID resource, UUID item, String parameter, RuntimeException cause, String detail) {
    super(detail, cause);
    diagnostic =
        new Diagnostic(
            "INVALID_NOISE",
            Diagnostic.Severity.ERROR,
            detail,
            "/noise/"
                + resource
                + (item == null ? "" : "/control/" + item)
                + (parameter == null
                    ? ""
                    : parameter.startsWith("inputs/")
                        ? "/inputs/" + parameter.substring(7).replace("~", "~0").replace("/", "~1")
                        : item == null && parameter.startsWith("algorithm/")
                            ? "/" + parameter
                            : "/parameters/" + parameter),
            resource,
            item,
            parameter);
  }

  public Diagnostic diagnostic() {
    return diagnostic;
  }
}

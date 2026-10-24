package io.voxelcreator.api;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

/** Bounded weighted priority lanes, each with round-robin scopes and FIFO work within a scope. */
final class FairWorkQueue extends AbstractQueue<Runnable> implements BlockingQueue<Runnable> {
  interface Work extends Runnable {
    Object scope();

    GenerationPriority priority();
  }

  private static final Object UNSCOPED = new Object();
  private static final int[] SERVICE = {0, 0, 1, 0, 2, 0, 1};

  private static final class Bucket {
    final Object owner;
    final ArrayDeque<Node> jobs = new ArrayDeque<>();

    Bucket(Object owner) {
      this.owner = owner;
    }
  }

  private static final class Node {
    final Runnable task;
    final Object owner;
    Bucket bucket;
    int lane;

    Node(Runnable task, Object owner, int lane) {
      this.task = task;
      this.owner = owner;
      this.lane = lane;
    }
  }

  private static final class Lane {
    final IdentityHashMap<Object, Bucket> owners = new IdentityHashMap<>();
    final ArrayDeque<Bucket> turns = new ArrayDeque<>();
    int size;
  }

  private final Lane[] lanes = {new Lane(), new Lane(), new Lane()};
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition notEmpty = lock.newCondition(), notFull = lock.newCondition();
  private final int capacity;
  private int count, cursor;
  private long dequeued, removed, rejected, promotions;

  FairWorkQueue(int capacity) {
    if (capacity < 1) throw new IllegalArgumentException("Queue capacity");
    this.capacity = capacity;
  }

  private static long increment(long value) {
    return value == Long.MAX_VALUE ? value : value + 1;
  }

  private void addNode(Node node) {
    var lane = lanes[node.lane];
    var bucket = lane.owners.get(node.owner);
    if (bucket == null) {
      bucket = new Bucket(node.owner);
      lane.owners.put(node.owner, bucket);
      lane.turns.addLast(bucket);
    }
    node.bucket = bucket;
    bucket.jobs.addLast(node);
    lane.size++;
  }

  private void enqueue(Runnable task) {
    Object owner = task instanceof Work work ? Objects.requireNonNull(work.scope()) : UNSCOPED;
    int priority =
        task instanceof Work work ? Objects.requireNonNull(work.priority()).ordinal() : 1;
    addNode(new Node(task, owner, priority));
    count++;
    notEmpty.signal();
  }

  private int lane() {
    int preferred = SERVICE[cursor];
    if (lanes[preferred].size > 0) return preferred;
    for (int i = 0; i < lanes.length; i++) if (lanes[i].size > 0) return i;
    return -1;
  }

  private Runnable extract() {
    int priority = lane();
    if (priority < 0) return null;
    cursor = (cursor + 1) % SERVICE.length;
    var lane = lanes[priority];
    var bucket = lane.turns.removeFirst();
    var node = bucket.jobs.removeFirst();
    if (bucket.jobs.isEmpty()) lane.owners.remove(bucket.owner);
    else lane.turns.addLast(bucket);
    lane.size--;
    count--;
    dequeued = increment(dequeued);
    notFull.signalAll();
    return node.task;
  }

  private void unlink(Node node) {
    var lane = lanes[node.lane];
    var bucket = node.bucket;
    bucket.jobs.remove(node);
    lane.size--;
    if (bucket.jobs.isEmpty()) {
      lane.owners.remove(bucket.owner);
      lane.turns.remove(bucket);
    }
  }

  boolean promote(Runnable task, GenerationPriority priority) {
    Objects.requireNonNull(priority);
    lock.lock();
    try {
      for (var lane : lanes)
        for (var bucket : lane.turns)
          for (var node : bucket.jobs)
            if (node.task == task && node.lane > priority.ordinal()) {
              unlink(node);
              node.lane = priority.ordinal();
              addNode(node);
              promotions = increment(promotions);
              return true;
            }
      return false;
    } finally {
      lock.unlock();
    }
  }

  SchedulerStats statistics() {
    lock.lock();
    try {
      var byPriority = new EnumMap<GenerationPriority, Integer>(GenerationPriority.class);
      var owners = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
      for (var p : GenerationPriority.values()) {
        byPriority.put(p, lanes[p.ordinal()].size);
        owners.addAll(lanes[p.ordinal()].owners.keySet());
      }
      return new SchedulerStats(
          count, capacity, owners.size(), byPriority, dequeued, removed, rejected, promotions);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean offer(Runnable task) {
    Objects.requireNonNull(task);
    lock.lock();
    try {
      if (count == capacity) {
        rejected = increment(rejected);
        return false;
      }
      enqueue(task);
      return true;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void put(Runnable task) throws InterruptedException {
    Objects.requireNonNull(task);
    lock.lockInterruptibly();
    try {
      while (count == capacity) notFull.await();
      enqueue(task);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean offer(Runnable task, long timeout, TimeUnit unit) throws InterruptedException {
    Objects.requireNonNull(task);
    long left = unit.toNanos(timeout);
    lock.lockInterruptibly();
    try {
      while (count == capacity) {
        if (left <= 0) {
          rejected = increment(rejected);
          return false;
        }
        left = notFull.awaitNanos(left);
      }
      enqueue(task);
      return true;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Runnable take() throws InterruptedException {
    lock.lockInterruptibly();
    try {
      while (count == 0) notEmpty.await();
      return extract();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Runnable poll(long timeout, TimeUnit unit) throws InterruptedException {
    long left = unit.toNanos(timeout);
    lock.lockInterruptibly();
    try {
      while (count == 0) {
        if (left <= 0) return null;
        left = notEmpty.awaitNanos(left);
      }
      return extract();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Runnable poll() {
    lock.lock();
    try {
      return extract();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Runnable peek() {
    lock.lock();
    try {
      int p = lane();
      return p < 0 ? null : lanes[p].turns.getFirst().jobs.getFirst().task;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int size() {
    lock.lock();
    try {
      return count;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int remainingCapacity() {
    lock.lock();
    try {
      return capacity - count;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean remove(Object task) {
    lock.lock();
    try {
      for (var lane : lanes)
        for (var bucket : lane.turns)
          for (var node : bucket.jobs)
            if (Objects.equals(task, node.task)) {
              unlink(node);
              count--;
              removed = increment(removed);
              notFull.signalAll();
              return true;
            }
      return false;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Iterator<Runnable> iterator() {
    var snapshot = new ArrayList<Runnable>();
    lock.lock();
    try {
      for (var lane : lanes)
        for (var bucket : lane.turns) for (var node : bucket.jobs) snapshot.add(node.task);
    } finally {
      lock.unlock();
    }
    var source = snapshot.iterator();
    return new Iterator<>() {
      Runnable last;

      public boolean hasNext() {
        return source.hasNext();
      }

      public Runnable next() {
        return last = source.next();
      }

      public void remove() {
        if (last == null) throw new IllegalStateException();
        FairWorkQueue.this.remove(last);
        last = null;
      }
    };
  }

  @Override
  public int drainTo(Collection<? super Runnable> destination) {
    return drainTo(destination, Integer.MAX_VALUE);
  }

  @Override
  public int drainTo(Collection<? super Runnable> destination, int maximum) {
    Objects.requireNonNull(destination);
    if (destination == this) throw new IllegalArgumentException("Cannot drain to self");
    lock.lock();
    try {
      int n = 0;
      while (n < maximum && count > 0) {
        Runnable task = peek();
        destination.add(task);
        extract();
        n++;
      }
      return n;
    } finally {
      lock.unlock();
    }
  }
}

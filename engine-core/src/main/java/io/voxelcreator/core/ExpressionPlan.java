package io.voxelcreator.core;

import io.voxelcreator.core.Expression.*;
import java.util.*;

/** Lazy register plan: successful values are shared only inside one immutable-input sample. */
final class ExpressionPlan {
  private enum Kind {
    CHECK,
    VALUE,
    VARIABLE,
    OPERATION,
    COPY,
    BRANCH_FALSE,
    BRANCH_TRUE,
    JUMP
  }

  private static final class Label {
    int position = -1;
  }

  private record Pending(
      Kind kind, Node node, int destination, int[] arguments, int variable, Label target) {}

  private record Instruction(
      Kind kind,
      String operation,
      int destination,
      int[] arguments,
      int variable,
      int target,
      double value,
      Function function,
      int start,
      int end) {}

  private record Key(
      Type type, String operation, String name, long value, List<Integer> arguments) {}

  private record Visit(Node node, boolean finish) {}

  private final Node root;
  private final Instruction[] code;
  private final List<String> inputs;
  private final Statistics statistics;
  private final int result, slots;
  private final ThreadLocal<State> scratch = ThreadLocal.withInitial(State::new);
  private int folded, pruned;

  ExpressionPlan(Node original, int parsedNodes) {
    root = optimize(original);
    var ids = new IdentityHashMap<Node, Integer>();
    var common = new HashMap<Key, Integer>();
    var names = new LinkedHashMap<String, Integer>();
    var pending = new ArrayDeque<Visit>();
    pending.push(new Visit(root, false));
    int shared = 0;
    while (!pending.isEmpty()) {
      var visit = pending.pop();
      var n = visit.node;
      if (ids.containsKey(n)) continue;
      if (!visit.finish) {
        pending.push(new Visit(n, true));
        for (int i = n.children.size() - 1; i >= 0; i--)
          pending.push(new Visit(n.children.get(i), false));
        continue;
      }
      var arguments = n.children.stream().map(ids::get).toList();
      var key =
          new Key(n.type, n.operation, n.name, Double.doubleToRawLongBits(n.value), arguments);
      Integer id = common.get(key);
      if (id == null) {
        id = common.size();
        common.put(key, id);
      } else if (!n.literal()) shared++;
      ids.put(n, id);
      if (n.operation.equals("variable")) names.computeIfAbsent(n.name, k -> names.size());
    }
    slots = common.size();
    result = ids.get(root);
    inputs = List.copyOf(names.keySet());
    var builder = new ArrayList<Pending>();
    var tasks = new ArrayDeque<Runnable>();
    tasks.push(() -> emit(root, ids, names, builder, tasks));
    while (!tasks.isEmpty()) tasks.pop().run();
    if (builder.size() > 32768) throw new Problem("Instruction limit 32768", root.start, root.end);
    code = new Instruction[builder.size()];
    for (int i = 0; i < code.length; i++) {
      var p = builder.get(i);
      var n = p.node;
      if (p.target != null && p.target.position < 0) throw new AssertionError("Unresolved branch");
      code[i] =
          new Instruction(
              p.kind,
              n.operation,
              p.destination,
              p.arguments,
              p.variable,
              p.target == null ? -1 : p.target.position,
              n.value,
              n.operation.equals("call") ? Expression.FUNCTIONS.get(n.name) : null,
              n.start,
              n.end);
    }
    statistics = new Statistics(parsedNodes, folded, pruned, shared, slots, code.length);
  }

  Type type() {
    return root.type;
  }

  List<String> inputs() {
    return inputs;
  }

  Statistics statistics() {
    return statistics;
  }

  OptionalDouble constantValue() {
    return root.literal() ? OptionalDouble.of(root.value) : OptionalDouble.empty();
  }

  private Node optimize(Node original) {
    var resolved = new IdentityHashMap<Node, Node>();
    var work = new ArrayDeque<Visit>();
    work.push(new Visit(original, false));
    while (!work.isEmpty()) {
      var visit = work.pop();
      var n = visit.node;
      if (resolved.containsKey(n)) continue;
      if (!visit.finish) {
        work.push(new Visit(n, true));
        for (int i = n.children.size() - 1; i >= 0; i--)
          work.push(new Visit(n.children.get(i), false));
        continue;
      }
      List<Node> children = n.children.stream().map(resolved::get).toList();
      Node output = new Node(n.type, n.operation, n.name, n.value, children, n.start, n.end);
      if (n.operation.equals("select") && children.getFirst().literal()) {
        output = children.get(children.getFirst().value != 0 ? 1 : 2);
        pruned++;
      } else if ((n.operation.equals("&&") || n.operation.equals("||"))
          && children.getFirst().literal()) {
        boolean left = children.getFirst().value != 0;
        if (n.operation.equals("&&") && !left || n.operation.equals("||") && left) {
          output = Node.literal(Type.Boolean, left ? 1 : 0, n.start, n.end);
          folded++;
        } else
          output =
              new Node(Type.Boolean, "truth", null, 0, List.of(children.get(1)), n.start, n.end);
        pruned++;
      }
      if (!output.literal()
          && !output.operation.equals("variable")
          && output.children.stream().allMatch(Node::literal)) {
        try {
          double[] values = output.children.stream().mapToDouble(c -> c.value).toArray();
          double value =
              calculate(
                  output.operation,
                  output.operation.equals("call") ? Expression.FUNCTIONS.get(output.name) : null,
                  values);
          if (Double.isFinite(value)) {
            output = Node.literal(output.type, value, output.start, output.end);
            folded++;
          }
        } catch (RuntimeException invalid) {
          /* Invalid constants stay at their executed source boundary. */
        }
      }
      resolved.put(n, output);
    }
    return resolved.get(original);
  }

  private static void emit(
      Node n,
      IdentityHashMap<Node, Integer> ids,
      Map<String, Integer> names,
      List<Pending> code,
      ArrayDeque<Runnable> tasks) {
    int destination = ids.get(n);
    var end = new Label();
    code.add(new Pending(Kind.CHECK, n, destination, new int[0], -1, end));
    tasks.push(() -> end.position = code.size());
    if (n.literal()) {
      code.add(new Pending(Kind.VALUE, n, destination, new int[0], -1, null));
      return;
    }
    if (n.operation.equals("variable")) {
      code.add(new Pending(Kind.VARIABLE, n, destination, new int[0], names.get(n.name), null));
      return;
    }
    if (n.operation.equals("select")) {
      var otherwise = new Label();
      Node condition = n.children.get(0), yes = n.children.get(1), no = n.children.get(2);
      tasks.push(
          () ->
              code.add(new Pending(Kind.COPY, n, destination, new int[] {ids.get(no)}, -1, null)));
      tasks.push(() -> emit(no, ids, names, code, tasks));
      tasks.push(() -> otherwise.position = code.size());
      tasks.push(() -> code.add(new Pending(Kind.JUMP, n, destination, new int[0], -1, end)));
      tasks.push(
          () ->
              code.add(new Pending(Kind.COPY, n, destination, new int[] {ids.get(yes)}, -1, null)));
      tasks.push(() -> emit(yes, ids, names, code, tasks));
      tasks.push(
          () ->
              code.add(
                  new Pending(
                      Kind.BRANCH_FALSE,
                      n,
                      destination,
                      new int[] {ids.get(condition)},
                      -1,
                      otherwise)));
      tasks.push(() -> emit(condition, ids, names, code, tasks));
      return;
    }
    if (n.operation.equals("&&") || n.operation.equals("||")) {
      var shortCircuit = new Label();
      Node a = n.children.get(0), b = n.children.get(1);
      boolean and = n.operation.equals("&&");
      Node value = Node.literal(Type.Boolean, and ? 0 : 1, n.start, n.end);
      tasks.push(() -> code.add(new Pending(Kind.VALUE, value, destination, new int[0], -1, null)));
      tasks.push(() -> shortCircuit.position = code.size());
      tasks.push(() -> code.add(new Pending(Kind.JUMP, n, destination, new int[0], -1, end)));
      Node truth = new Node(Type.Boolean, "truth", null, 0, List.of(), n.start, n.end);
      tasks.push(
          () ->
              code.add(
                  new Pending(
                      Kind.OPERATION, truth, destination, new int[] {ids.get(b)}, -1, null)));
      tasks.push(() -> emit(b, ids, names, code, tasks));
      tasks.push(
          () ->
              code.add(
                  new Pending(
                      and ? Kind.BRANCH_FALSE : Kind.BRANCH_TRUE,
                      n,
                      destination,
                      new int[] {ids.get(a)},
                      -1,
                      shortCircuit)));
      tasks.push(() -> emit(a, ids, names, code, tasks));
      return;
    }
    int[] arguments = n.children.stream().mapToInt(ids::get).toArray();
    tasks.push(() -> code.add(new Pending(Kind.OPERATION, n, destination, arguments, -1, null)));
    for (int i = n.children.size() - 1; i >= 0; i--) {
      Node child = n.children.get(i);
      tasks.push(() -> emit(child, ids, names, code, tasks));
    }
  }

  private static double calculate(String operation, Function function, double[] a) {
    return switch (operation) {
      case "call" -> function.implementation().applyAsDouble(a);
      case "select" -> a[0] != 0 ? a[1] : a[2];
      case "truth" -> a[0] != 0 ? 1 : 0;
      case "positive" -> a[0];
      case "negate" -> -a[0];
      case "not" -> a[0] == 0 ? 1 : 0;
      case "+" -> a[0] + a[1];
      case "-" -> a[0] - a[1];
      case "*" -> a[0] * a[1];
      case "/" -> a[0] / a[1];
      case "%" -> a[0] % a[1];
      case "^" -> StrictMath.pow(a[0], a[1]);
      case "<" -> a[0] < a[1] ? 1 : 0;
      case "<=" -> a[0] <= a[1] ? 1 : 0;
      case ">" -> a[0] > a[1] ? 1 : 0;
      case ">=" -> a[0] >= a[1] ? 1 : 0;
      case "==" -> a[0] == a[1] ? 1 : 0;
      case "!=" -> a[0] != a[1] ? 1 : 0;
      case "&&" -> a[0] != 0 && a[1] != 0 ? 1 : 0;
      case "||" -> a[0] != 0 || a[1] != 0 ? 1 : 0;
      default -> throw new AssertionError(operation);
    };
  }

  private static final class Frame {
    final double[] values,
        args =
            new double
                [Math.max(
                    3,
                    Expression.FUNCTIONS.values().stream()
                        .mapToInt(Function::arity)
                        .max()
                        .orElse(0))];
    final int[] stamps;
    int epoch, sample, mode;
    Variables named;
    IndexedVariables indexed;
    BatchVariables batch;

    Frame(int slots) {
      values = new double[slots];
      stamps = new int[slots];
    }

    void next() {
      if (++epoch == 0) {
        Arrays.fill(stamps, 0);
        epoch = 1;
      }
    }

    void clear() {
      named = null;
      indexed = null;
      batch = null;
    }
  }

  private static final class State {
    final ArrayList<Frame> frames = new ArrayList<>();
    int depth;

    Frame acquire(int slots, int start, int end) {
      if (depth >= 64) throw new Problem("Host evaluation nesting limit 64", start, end);
      if (depth == frames.size()) frames.add(new Frame(slots));
      return frames.get(depth++);
    }

    void release(Frame frame) {
      frame.clear();
      depth--;
    }
  }

  double named(Variables variables) {
    if (root.literal()) return root.value;
    var state = scratch.get();
    var frame = state.acquire(slots, root.start, root.end);
    try {
      frame.mode = 0;
      frame.named = variables;
      frame.next();
      return execute(frame);
    } finally {
      state.release(frame);
    }
  }

  double indexed(IndexedVariables variables) {
    if (root.literal()) return root.value;
    var state = scratch.get();
    var frame = state.acquire(slots, root.start, root.end);
    try {
      frame.mode = 1;
      frame.indexed = variables;
      frame.next();
      return execute(frame);
    } finally {
      state.release(frame);
    }
  }

  void batch(
      int count, BatchVariables variables, double[] numbers, boolean[] booleans, int offset) {
    int length = numbers == null ? Objects.requireNonNull(booleans).length : numbers.length;
    if (count < 0 || offset < 0 || offset > length || count > length - offset)
      throw new IllegalArgumentException("Expression batch count/offset/destination");
    var state = scratch.get();
    var frame = state.acquire(slots, root.start, root.end);
    try {
      frame.mode = 2;
      frame.batch = variables;
      for (int i = 0; i < count; i++) {
        frame.sample = i;
        frame.next();
        double value;
        try {
          value = execute(frame);
        } catch (RuntimeException failure) {
          throw new BatchFailure(i, failure);
        }
        if (numbers != null) numbers[offset + i] = value;
        else booleans[offset + i] = value != 0;
      }
    } finally {
      state.release(frame);
    }
  }

  private double execute(Frame frame) {
    var values = frame.values;
    for (int pc = 0; pc < code.length; ) {
      var instruction = code[pc];
      int destination = instruction.destination;
      double value;
      switch (instruction.kind) {
        case CHECK -> {
          pc = frame.stamps[destination] == frame.epoch ? instruction.target : pc + 1;
          continue;
        }
        case JUMP -> {
          pc = instruction.target;
          continue;
        }
        case BRANCH_FALSE -> {
          pc = values[instruction.arguments[0]] == 0 ? instruction.target : pc + 1;
          continue;
        }
        case BRANCH_TRUE -> {
          pc = values[instruction.arguments[0]] != 0 ? instruction.target : pc + 1;
          continue;
        }
        case VALUE -> value = instruction.value;
        case COPY -> value = values[instruction.arguments[0]];
        case VARIABLE ->
            value =
                switch (frame.mode) {
                  case 0 -> frame.named.get(inputs.get(instruction.variable));
                  case 1 -> frame.indexed.get(instruction.variable);
                  default -> frame.batch.get(inputs.get(instruction.variable), frame.sample);
                };
        case OPERATION -> {
          for (int i = 0; i < instruction.arguments.length; i++)
            frame.args[i] = values[instruction.arguments[i]];
          try {
            value = calculate(instruction.operation, instruction.function, frame.args);
          } catch (IllegalArgumentException failure) {
            throw new Problem(failure.getMessage(), instruction.start, instruction.end);
          }
        }
        default -> throw new AssertionError();
      }
      if (!Double.isFinite(value))
        throw new Problem(
            "Nonfinite result or invalid numeric domain", instruction.start, instruction.end);
      values[destination] = value;
      frame.stamps[destination] = frame.epoch;
      pc++;
    }
    return values[result];
  }
}

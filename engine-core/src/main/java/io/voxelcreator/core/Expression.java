package io.voxelcreator.core;

import java.util.*;
import java.util.function.*;

/**
 * Bounded stateless Scalar/Boolean language. Power is right associative; unary minus binds below
 * power.
 */
public final class Expression {
  public enum Type {
    Scalar,
    Boolean
  }

  public static final class Problem extends IllegalArgumentException {
    public final int start, end;

    public Problem(String m, int s, int e) {
      super(m + " at " + s + ".." + e);
      start = s;
      end = e;
    }
  }

  @FunctionalInterface
  public interface Variables {
    double get(String name);
  }

  /** Inputs are immutable within one evaluation; Boolean inputs conventionally use 0/1. */
  @FunctionalInterface
  public interface IndexedVariables {
    double get(int index);
  }

  @FunctionalInterface
  public interface BatchVariables {
    double get(String name, int sample);
  }

  public record Statistics(
      int parsedNodes,
      int foldedNodes,
      int prunedBranches,
      int sharedExpressions,
      int valueSlots,
      int instructions) {}

  public static final class BatchFailure extends IllegalArgumentException {
    public final int sampleIndex;

    public BatchFailure(int sampleIndex, RuntimeException cause) {
      super("Expression batch sample " + sampleIndex + ": " + cause.getMessage(), cause);
      this.sampleIndex = sampleIndex;
    }
  }

  public static final class Program {
    private final ExpressionPlan plan;

    private Program(Node node, int parsedNodes) {
      plan = new ExpressionPlan(node, parsedNodes);
    }

    public Type type() {
      return plan.type();
    }

    public List<String> inputs() {
      return plan.inputs();
    }

    public Statistics statistics() {
      return plan.statistics();
    }

    public OptionalDouble constantValue() {
      return plan.constantValue();
    }

    private void require(Type type) {
      if (type() != type)
        throw new IllegalStateException(
            type == Type.Scalar ? "Boolean expression" : "Scalar expression");
    }

    public double scalar(Variables variables) {
      require(Type.Scalar);
      return plan.named(variables);
    }

    public boolean bool(Variables variables) {
      require(Type.Boolean);
      return plan.named(variables) != 0;
    }

    public double scalarIndexed(IndexedVariables variables) {
      require(Type.Scalar);
      return plan.indexed(variables);
    }

    public boolean boolIndexed(IndexedVariables variables) {
      require(Type.Boolean);
      return plan.indexed(variables) != 0;
    }

    public void scalars(int count, BatchVariables variables, double[] destination, int offset) {
      require(Type.Scalar);
      plan.batch(count, variables, destination, null, offset);
    }

    public void booleans(int count, BatchVariables variables, boolean[] destination, int offset) {
      require(Type.Boolean);
      plan.batch(count, variables, null, destination, offset);
    }
  }

  static final class Node {
    final Type type;
    final String operation, name;
    final List<Node> children;
    final double value;
    final int start, end;

    Node(
        Type type,
        String operation,
        String name,
        double value,
        List<Node> children,
        int start,
        int end) {
      this.type = type;
      this.operation = operation;
      this.name = name;
      this.value = value;
      this.children = List.copyOf(children);
      this.start = start;
      this.end = end;
    }

    static Node literal(Type type, double value, int start, int end) {
      return new Node(type, "literal", null, value, List.of(), start, end);
    }

    boolean literal() {
      return operation.equals("literal");
    }
  }

  private record Token(String text, int start, int end) {}

  public record Function(int arity, ToDoubleFunction<double[]> implementation) {}

  public static final Map<String, Function> FUNCTIONS;

  static {
    Map<String, Function> m = new LinkedHashMap<>();
    unary(m, "sin", StrictMath::sin);
    unary(m, "cos", StrictMath::cos);
    unary(m, "tan", StrictMath::tan);
    unary(m, "asin", StrictMath::asin);
    unary(m, "atan", StrictMath::atan);
    unary(m, "sinh", StrictMath::sinh);
    unary(m, "cosh", StrictMath::cosh);
    unary(m, "tanh", StrictMath::tanh);
    unary(m, "abs", StrictMath::abs);
    unary(m, "floor", StrictMath::floor);
    unary(m, "ceil", StrictMath::ceil);
    unary(m, "round", x -> StrictMath.floor(x + 0.5));
    unary(m, "fract", x -> x - StrictMath.floor(x));
    unary(m, "sqrt", StrictMath::sqrt);
    unary(m, "cbrt", StrictMath::cbrt);
    unary(m, "exp", StrictMath::exp);
    unary(m, "log", StrictMath::log);
    unary(m, "log10", StrictMath::log10);
    unary(m, "sign", StrictMath::signum);
    unary(m, "saturate", x -> clamp(x, 0, 1));
    binary(m, "min", StrictMath::min);
    binary(m, "max", StrictMath::max);
    binary(m, "pow", StrictMath::pow);
    binary(m, "atan2", StrictMath::atan2);
    binary(m, "mod", (a, b) -> a - b * StrictMath.floor(a / b));
    binary(m, "step", (edge, x) -> x < edge ? 0 : 1);
    binary(
        m,
        "pingPong",
        (x, length) -> {
          double t = x - 2 * length * StrictMath.floor(x / (2 * length));
          return length - StrictMath.abs(t - length);
        });
    m.put("clamp", new Function(3, a -> clamp(a[0], a[1], a[2])));
    m.put("lerp", new Function(3, a -> a[0] + (a[1] - a[0]) * a[2]));
    m.put("inverseLerp", new Function(3, a -> (a[2] - a[0]) / (a[1] - a[0])));
    m.put(
        "smoothstep",
        new Function(
            3,
            a -> {
              double t = clamp((a[2] - a[0]) / (a[1] - a[0]), 0, 1);
              return t * t * (3 - 2 * t);
            }));
    m.put("remap", new Function(5, a -> a[3] + (a[0] - a[1]) / (a[2] - a[1]) * (a[4] - a[3])));
    m.put(
        "wrap",
        new Function(
            3, a -> a[0] - (a[2] - a[1]) * StrictMath.floor((a[0] - a[1]) / (a[2] - a[1]))));
    FUNCTIONS = Collections.unmodifiableMap(m);
  }

  private static void unary(Map<String, Function> m, String n, DoubleUnaryOperator f) {
    m.put(n, new Function(1, a -> f.applyAsDouble(a[0])));
  }

  private static void binary(Map<String, Function> m, String n, DoubleBinaryOperator f) {
    m.put(n, new Function(2, a -> f.applyAsDouble(a[0], a[1])));
  }

  public static double clamp(double x, double lo, double hi) {
    if (lo > hi) throw new IllegalArgumentException("Inverted clamp range");
    return StrictMath.max(lo, StrictMath.min(hi, x));
  }

  public static Program compile(String source, Map<String, Type> vars) {
    return compile(source, vars, Map.of());
  }

  public static Program compile(
      String source, Map<String, Type> vars, Map<String, Double> constants) {
    var types = Map.copyOf(vars);
    var values = Map.copyOf(constants);
    values.forEach(
        (name, value) -> {
          if (!types.containsKey(name)
              || !Double.isFinite(value)
              || (types.get(name) == Type.Boolean && value != 0 && value != 1))
            throw new IllegalArgumentException("Invalid constant input " + name);
        });
    var parser = new Parser(source, types, values);
    var root = parser.parse();
    return new Program(root, parser.nodes);
  }

  private static final class Parser {
    final List<Token> tokens = new ArrayList<>();
    final Map<String, Type> vars;
    final Map<String, Double> constants;
    int at = 0, depth = 0, nodes = 0;

    Parser(String s, Map<String, Type> vars, Map<String, Double> constants) {
      this.vars = vars;
      this.constants = constants;
      if (s == null || s.length() > 16384) throw new Problem("Expression length limit 16384", 0, 0);
      int i = 0;
      while (i < s.length()) {
        char c = s.charAt(i);
        if (Character.isWhitespace(c)) {
          i++;
          continue;
        }
        int start = i;
        if (Character.isDigit(c) || c == '.') {
          i++;
          while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
          if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            i++;
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
          }
        } else if (Character.isLetter(c) || c == '_') {
          i++;
          while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_'))
            i++;
        } else {
          i++;
          if (i < s.length()
              && Set.of("<=", ">=", "==", "!=", "&&", "||").contains(s.substring(start, i + 1)))
            i++;
          else if ("+-*/%^<>()!?:,".indexOf(c) < 0)
            throw new Problem("Unsupported character", start, i);
        }
        tokens.add(new Token(s.substring(start, i), start, i));
        if (tokens.size() > 4096) throw new Problem("Token limit 4096", start, i);
      }
      tokens.add(new Token("<end>", s.length(), s.length()));
    }

    Token peek() {
      return tokens.get(at);
    }

    Token take() {
      if (at >= tokens.size() - 1) throw problem("Expected expression", tokens.getLast());
      return tokens.get(at++);
    }

    boolean accept(String t) {
      if (peek().text.equals(t)) {
        at++;
        return true;
      }
      return false;
    }

    void expect(String t) {
      if (!accept(t)) throw problem("Expected '" + t + "'", peek());
    }

    Problem problem(String m, Token t) {
      return new Problem(m, t.start, t.end);
    }

    Node node(
        Type type,
        String operation,
        String name,
        double value,
        List<Node> children,
        int start,
        int end) {
      if (++nodes > 4096) throw new Problem("AST size limit", start, end);
      return new Node(type, operation, name, value, children, start, end);
    }

    Node literal(Type type, double value, int start, int end) {
      return node(type, "literal", null, value, List.of(), start, end);
    }

    Node operation(Type type, String operation, List<Node> children, int start, int end) {
      return node(type, operation, null, 0, children, start, end);
    }

    Node parse() {
      Node n = expr(0);
      if (!peek().text.equals("<end>")) throw problem("Unexpected token", peek());
      return n;
    }

    Node expr(int min) {
      if (++depth > 64) throw problem("AST depth limit 64", peek());
      Token t = take();
      Node left;
      if (t.text.equals("(")) {
        left = expr(0);
        expect(")");
      } else if (Set.of("+", "-", "!").contains(t.text)) {
        Node a = expr(7);
        Type required = t.text.equals("!") ? Type.Boolean : Type.Scalar;
        require(a, required, t);
        left =
            operation(
                required,
                t.text.equals("!") ? "not" : t.text.equals("-") ? "negate" : "positive",
                List.of(a),
                t.start,
                a.end);
      } else if (t.text.equals("true") || t.text.equals("false")) {
        double x = t.text.equals("true") ? 1 : 0;
        left = literal(Type.Boolean, x, t.start, t.end);
      } else if (Set.of("pi", "e", "tau").contains(t.text)) {
        double x =
            t.text.equals("e")
                ? StrictMath.E
                : t.text.equals("pi") ? StrictMath.PI : 2 * StrictMath.PI;
        left = literal(Type.Scalar, x, t.start, t.end);
      } else if (Character.isDigit(t.text.charAt(0)) || t.text.charAt(0) == '.') {
        try {
          double x = Double.parseDouble(t.text);
          if (!Double.isFinite(x)) throw new NumberFormatException();
          left = literal(Type.Scalar, x, t.start, t.end);
        } catch (NumberFormatException e) {
          throw problem("Invalid finite number", t);
        }
      } else if (accept("(")) {
        var f = FUNCTIONS.get(t.text);
        if (f == null) throw problem("Unknown function '" + t.text + "'", t);
        var args = new ArrayList<Node>();
        if (!accept(")")) {
          do {
            args.add(expr(0));
          } while (accept(","));
          expect(")");
        }
        if (args.size() != f.arity) throw problem("Expected " + f.arity + " arguments", t);
        for (Node a : args) require(a, Type.Scalar, t);
        left = node(Type.Scalar, "call", t.text, 0, args, t.start, tokens.get(at - 1).end);
      } else {
        Type type = vars.get(t.text);
        if (type == null) throw problem("Unknown input '" + t.text + "'", t);
        left =
            constants.containsKey(t.text)
                ? literal(type, constants.get(t.text), t.start, t.end)
                : node(type, "variable", t.text, 0, List.of(), t.start, t.end);
      }
      while (true) {
        Token op = peek();
        int precedence = precedence(op.text);
        if (precedence < min) break;
        take();
        Node a = left;
        if (op.text.equals("?")) {
          require(a, Type.Boolean, op);
          Node yes = expr(0);
          expect(":");
          Node no = expr(0);
          if (yes.type != no.type) throw problem("Ternary branches must have matching types", op);
          left = operation(yes.type, "select", List.of(a, yes, no), a.start, no.end);
          continue;
        }
        Node b = expr(precedence + (op.text.equals("^") ? 0 : 1));
        boolean logic = Set.of("&&", "||").contains(op.text),
            equality = Set.of("==", "!=").contains(op.text),
            compare = Set.of("<", "<=", ">", ">=").contains(op.text);
        if (equality) {
          if (a.type != b.type) throw problem("Equality requires matching types", op);
        } else {
          require(a, logic ? Type.Boolean : Type.Scalar, op);
          require(b, logic ? Type.Boolean : Type.Scalar, op);
        }
        left =
            operation(
                logic || equality || compare ? Type.Boolean : Type.Scalar,
                op.text,
                List.of(a, b),
                a.start,
                b.end);
      }
      depth--;
      return left;
    }

    static void require(Node n, Type t, Token token) {
      if (n.type != t)
        throw new Problem("Expected " + t + ", received " + n.type, token.start, token.end);
    }

    static int precedence(String s) {
      return switch (s) {
        case "?" -> 0;
        case "||" -> 1;
        case "&&" -> 2;
        case "==", "!=" -> 3;
        case "<", "<=", ">", ">=" -> 4;
        case "+", "-" -> 5;
        case "*", "/", "%" -> 6;
        case "^" -> 8;
        default -> -1;
      };
    }
  }
}

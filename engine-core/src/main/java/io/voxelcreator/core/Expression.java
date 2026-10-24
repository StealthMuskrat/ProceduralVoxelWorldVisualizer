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

  @FunctionalInterface
  private interface Eval {
    double eval(Variables variables);
  }

  public static final class Program {
    private final Node node;

    private Program(Node n) {
      node = n;
    }

    public Type type() {
      return node.type;
    }

    public double scalar(Variables v) {
      if (type() != Type.Scalar) throw new IllegalStateException("Boolean expression");
      return node.run(v);
    }

    public boolean bool(Variables v) {
      if (type() != Type.Boolean) throw new IllegalStateException("Scalar expression");
      return node.run(v) != 0;
    }
  }

  private record Node(Type type, Eval fn, int start, int end) {
    double run(Variables v) {
      double d = fn.eval(v);
      if (!Double.isFinite(d))
        throw new Problem("Nonfinite result or invalid numeric domain", start, end);
      return d;
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
    return new Program(new Parser(source, Map.copyOf(vars)).parse());
  }

  private static final class Parser {
    final List<Token> tokens = new ArrayList<>();
    final Map<String, Type> vars;
    int at = 0, depth = 0, nodes = 0;

    Parser(String s, Map<String, Type> vars) {
      this.vars = vars;
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

    Node node(Type t, Eval e, int s, int end) {
      if (++nodes > 4096) throw new Problem("AST size limit", s, end);
      return new Node(t, e, s, end);
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
            node(
                required,
                v ->
                    t.text.equals("!")
                        ? (a.run(v) == 0 ? 1 : 0)
                        : t.text.equals("-") ? -a.run(v) : a.run(v),
                t.start,
                a.end);
      } else if (t.text.equals("true") || t.text.equals("false")) {
        double x = t.text.equals("true") ? 1 : 0;
        left = node(Type.Boolean, v -> x, t.start, t.end);
      } else if (Set.of("pi", "e", "tau").contains(t.text)) {
        double x =
            t.text.equals("e")
                ? StrictMath.E
                : t.text.equals("pi") ? StrictMath.PI : 2 * StrictMath.PI;
        left = node(Type.Scalar, v -> x, t.start, t.end);
      } else if (Character.isDigit(t.text.charAt(0)) || t.text.charAt(0) == '.') {
        try {
          double x = Double.parseDouble(t.text);
          if (!Double.isFinite(x)) throw new NumberFormatException();
          left = node(Type.Scalar, v -> x, t.start, t.end);
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
        // One argument buffer per compiled node per thread, never per sample.
        ThreadLocal<double[]> scratch = ThreadLocal.withInitial(() -> new double[args.size()]);
        left =
            node(
                Type.Scalar,
                v -> {
                  double[] a = scratch.get();
                  for (int j = 0; j < a.length; j++) a[j] = args.get(j).run(v);
                  return f.implementation.applyAsDouble(a);
                },
                t.start,
                tokens.get(at - 1).end);
      } else {
        Type type = vars.get(t.text);
        if (type == null) throw problem("Unknown input '" + t.text + "'", t);
        left = node(type, v -> v.get(t.text), t.start, t.end);
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
          left = node(yes.type, v -> a.run(v) != 0 ? yes.run(v) : no.run(v), a.start, no.end);
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
            node(
                logic || equality || compare ? Type.Boolean : Type.Scalar,
                v -> apply(op.text, a, b, v),
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

    static double apply(String op, Node a, Node b, Variables v) {
      double x = a.run(v);
      if (op.equals("&&")) return x != 0 && b.run(v) != 0 ? 1 : 0;
      if (op.equals("||")) return x != 0 || b.run(v) != 0 ? 1 : 0;
      double y = b.run(v);
      return switch (op) {
        case "+" -> x + y;
        case "-" -> x - y;
        case "*" -> x * y;
        case "/" -> x / y;
        case "%" -> x % y;
        case "^" -> StrictMath.pow(x, y);
        case "<" -> x < y ? 1 : 0;
        case "<=" -> x <= y ? 1 : 0;
        case ">" -> x > y ? 1 : 0;
        case ">=" -> x >= y ? 1 : 0;
        case "==" -> x == y ? 1 : 0;
        case "!=" -> x != y ? 1 : 0;
        default -> throw new AssertionError(op);
      };
    }
  }
}

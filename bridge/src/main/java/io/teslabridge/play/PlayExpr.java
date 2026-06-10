package io.teslabridge.play;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Play Engine 自研 mini 表达式语言（设计定稿 §1.1）——刻意不用 SpEL。
 *
 * <p>能力面（白名单，超出即编译失败）：
 *
 * <ul>
 *   <li>算术：{@code + - * / ( )}，一元负号
 *   <li>数字字面量（int / float）
 *   <li>标识符引用 {@code [a-z_][a-z0-9_]*}（SQL 返回列 / 前序 compute var / 内置 window_days）
 *   <li>三个函数：{@code GREATEST / LEAST}（变长 ≥1 参）、{@code ROUND}（1 或 2 参）
 * </ul>
 *
 * <p><b>为什么不用 SpEL</b>：play.yaml 有 classpath（PR review 把关）与 {@code PLAYS_DIR}
 * 外部目录（ECS 热修，信任弱于 repo）两条加载路径，必须按"半信任输入"设防。SpEL 即使用
 * {@code SimpleEvaluationContext} 仍保留方法调用等远超需求的能力面（CVE-2022-22963 等
 * 前科），且 GREATEST/ROUND 在 SpEL 里要走 {@code T(Math)} —— 恰是必须封死的入口。
 *
 * <p>语义定案：
 *
 * <ul>
 *   <li>除零返回 {@code 0} 并打 WARN（保证卡片可渲染）
 *   <li>编译期不校验标识符是否存在（SQL 列运行时才知道），由 fixtures 在 CI 保证可执行
 *   <li>运行时未知标识符 → 抛 {@link PlayComputeException}（controller 层转 500
 *       PLAY_COMPUTE_ERROR + ERROR log）
 * </ul>
 */
public final class PlayExpr {

    private static final Logger log = LoggerFactory.getLogger(PlayExpr.class);

    private final String source;
    private final Node root;

    private PlayExpr(String source, Node root) {
        this.source = source;
        this.root = root;
    }

    /** 编译表达式；语法错误抛 {@link PlayLoadException}（加载期 → 跳过该 play）。 */
    public static PlayExpr compile(String source) {
        if (source == null || source.isBlank()) {
            throw new PlayLoadException("expr 为空");
        }
        Parser p = new Parser(source);
        Node root = p.parseExpr();
        if (p.pos < p.tokens.size()) {
            throw new PlayLoadException(
                    "expr 末尾有多余 token: '" + p.tokens.get(p.pos).text + "' in: " + source);
        }
        return new PlayExpr(source, root);
    }

    public String source() {
        return source;
    }

    /**
     * 求值。{@code ctx} 的 value 须为 {@link Number}（SQL NULL → 按 0 处理并 WARN，见
     * {@link #toNumber}）；标识符不在 ctx → {@link PlayComputeException}。
     */
    public double eval(Map<String, Object> ctx) {
        return root.eval(ctx);
    }

    private static double toNumber(String ident, Object v) {
        if (v == null) {
            // SQL 聚合在空窗口可能返 NULL 列（min_sample gate 之外的列）。按"卡片可渲染"
            // 哲学与除零同策略：取 0 + WARN，而不是 500。
            log.warn("play expr: identifier '{}' is SQL NULL, treated as 0", ident);
            return 0.0;
        }
        if (v instanceof Number n) return n.doubleValue();
        throw new PlayComputeException(
                "identifier '" + ident + "' is not numeric (" + v.getClass().getSimpleName() + ")");
    }

    // ====== AST ======

    private sealed interface Node permits Num, Ident, Bin, Neg, Func {
        double eval(Map<String, Object> ctx);
    }

    private record Num(double v) implements Node {
        @Override
        public double eval(Map<String, Object> ctx) {
            return v;
        }
    }

    private record Ident(String name) implements Node {
        @Override
        public double eval(Map<String, Object> ctx) {
            if (!ctx.containsKey(name)) {
                throw new PlayComputeException("unknown identifier '" + name + "'");
            }
            return toNumber(name, ctx.get(name));
        }
    }

    private record Bin(char op, Node l, Node r) implements Node {
        @Override
        public double eval(Map<String, Object> ctx) {
            double a = l.eval(ctx);
            double b = r.eval(ctx);
            return switch (op) {
                case '+' -> a + b;
                case '-' -> a - b;
                case '*' -> a * b;
                case '/' -> {
                    if (b == 0.0) {
                        // 设计定稿 §1.1：除零返 0 + WARN，保证卡片可渲染。
                        log.warn("play expr: division by zero → 0");
                        yield 0.0;
                    }
                    yield a / b;
                }
                default -> throw new PlayComputeException("bad op " + op);
            };
        }
    }

    private record Neg(Node n) implements Node {
        @Override
        public double eval(Map<String, Object> ctx) {
            return -n.eval(ctx);
        }
    }

    private record Func(String fn, List<Node> args) implements Node {
        @Override
        public double eval(Map<String, Object> ctx) {
            return switch (fn) {
                case "GREATEST" -> {
                    double m = args.get(0).eval(ctx);
                    for (int i = 1; i < args.size(); i++) m = Math.max(m, args.get(i).eval(ctx));
                    yield m;
                }
                case "LEAST" -> {
                    double m = args.get(0).eval(ctx);
                    for (int i = 1; i < args.size(); i++) m = Math.min(m, args.get(i).eval(ctx));
                    yield m;
                }
                case "ROUND" -> {
                    double v = args.get(0).eval(ctx);
                    if (args.size() == 1) yield Math.round(v);
                    double scale = Math.pow(10, (int) args.get(1).eval(ctx));
                    yield Math.round(v * scale) / scale;
                }
                default -> throw new PlayComputeException("bad fn " + fn);
            };
        }
    }

    // ====== Tokenizer + 递归下降 ======

    private record Token(Type type, String text) {
        enum Type {
            NUM,
            IDENT,
            FUNC,
            OP,
            LPAREN,
            RPAREN,
            COMMA
        }
    }

    private static final class Parser {
        final List<Token> tokens;
        int pos = 0;

        Parser(String src) {
            this.tokens = tokenize(src);
        }

        static List<Token> tokenize(String src) {
            List<Token> out = new ArrayList<>();
            int i = 0;
            int n = src.length();
            while (i < n) {
                char c = src.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (c >= '0' && c <= '9' || c == '.') {
                    int s = i;
                    while (i < n && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '.')) i++;
                    String t = src.substring(s, i);
                    try {
                        Double.parseDouble(t);
                    } catch (NumberFormatException e) {
                        throw new PlayLoadException("非法数字字面量 '" + t + "'");
                    }
                    out.add(new Token(Token.Type.NUM, t));
                } else if (c >= 'a' && c <= 'z' || c == '_') {
                    int s = i;
                    while (i < n && isIdentChar(src.charAt(i))) i++;
                    out.add(new Token(Token.Type.IDENT, src.substring(s, i)));
                } else if (c >= 'A' && c <= 'Z') {
                    int s = i;
                    while (i < n && src.charAt(i) >= 'A' && src.charAt(i) <= 'Z') i++;
                    String fn = src.substring(s, i);
                    if (!fn.equals("GREATEST") && !fn.equals("LEAST") && !fn.equals("ROUND")) {
                        throw new PlayLoadException(
                                "未知函数 '" + fn + "'（仅支持 GREATEST/LEAST/ROUND）");
                    }
                    out.add(new Token(Token.Type.FUNC, fn));
                } else if (c == '+' || c == '-' || c == '*' || c == '/') {
                    out.add(new Token(Token.Type.OP, String.valueOf(c)));
                    i++;
                } else if (c == '(') {
                    out.add(new Token(Token.Type.LPAREN, "("));
                    i++;
                } else if (c == ')') {
                    out.add(new Token(Token.Type.RPAREN, ")"));
                    i++;
                } else if (c == ',') {
                    out.add(new Token(Token.Type.COMMA, ","));
                    i++;
                } else {
                    throw new PlayLoadException("非法字符 '" + c + "' in expr");
                }
            }
            return out;
        }

        static boolean isIdentChar(char c) {
            return c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_';
        }

        Token peek() {
            return pos < tokens.size() ? tokens.get(pos) : null;
        }

        Token next() {
            if (pos >= tokens.size()) throw new PlayLoadException("expr 意外结束");
            return tokens.get(pos++);
        }

        void expect(Token.Type t, String what) {
            Token tok = next();
            if (tok.type != t) {
                throw new PlayLoadException("期望 " + what + " 但遇到 '" + tok.text + "'");
            }
        }

        // expr := term (('+'|'-') term)*
        Node parseExpr() {
            Node l = parseTerm();
            while (peek() != null
                    && peek().type == Token.Type.OP
                    && (peek().text.equals("+") || peek().text.equals("-"))) {
                char op = next().text.charAt(0);
                l = new Bin(op, l, parseTerm());
            }
            return l;
        }

        // term := unary (('*'|'/') unary)*
        Node parseTerm() {
            Node l = parseUnary();
            while (peek() != null
                    && peek().type == Token.Type.OP
                    && (peek().text.equals("*") || peek().text.equals("/"))) {
                char op = next().text.charAt(0);
                l = new Bin(op, l, parseUnary());
            }
            return l;
        }

        // unary := '-' unary | primary
        Node parseUnary() {
            if (peek() != null && peek().type == Token.Type.OP && peek().text.equals("-")) {
                next();
                return new Neg(parseUnary());
            }
            return parsePrimary();
        }

        // primary := NUM | IDENT | FUNC '(' expr (',' expr)* ')' | '(' expr ')'
        Node parsePrimary() {
            Token t = next();
            return switch (t.type) {
                case NUM -> new Num(Double.parseDouble(t.text));
                case IDENT -> new Ident(t.text);
                case FUNC -> {
                    expect(Token.Type.LPAREN, "'(' after " + t.text);
                    List<Node> args = new ArrayList<>();
                    args.add(parseExpr());
                    while (peek() != null && peek().type == Token.Type.COMMA) {
                        next();
                        args.add(parseExpr());
                    }
                    expect(Token.Type.RPAREN, "')'");
                    if (t.text.equals("ROUND") && args.size() > 2) {
                        throw new PlayLoadException("ROUND 最多 2 参");
                    }
                    yield new Func(t.text, args);
                }
                case LPAREN -> {
                    Node inner = parseExpr();
                    expect(Token.Type.RPAREN, "')'");
                    yield inner;
                }
                default -> throw new PlayLoadException("非法 token '" + t.text + "'");
            };
        }
    }
}

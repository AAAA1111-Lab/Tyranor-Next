package com.core.gl;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Behavioral CPU check of the emitted coordinate wrapper. The fragment source produced by
 * {@link MpvHookShader#fragmentSource} is parsed and evaluated numerically, so any edit to the
 * emitted hookCoord algebra must keep the declared-row-order semantics for every combination of
 * descriptor declaration and physical input row order, or this test fails.
 */
public final class FilterCoordinateWrapperTest {

    @Test public void flipDecisionCoversEveryDeclaredAndPhysicalCombination() {
        assertEquals(0, Anime4kPostProcessor.flipYUniform(true, true));
        assertEquals(1, Anime4kPostProcessor.flipYUniform(true, false));
        assertEquals(1, Anime4kPostProcessor.flipYUniform(false, true));
        assertEquals(0, Anime4kPostProcessor.flipYUniform(false, false));
    }

    @Test public void emittedWrapperSemanticsMatchDeclaredRowOrderAcrossCombinations() {
        MpvHookShader.Pass pass = MpvHookShader.parse(
                "//!DESC wrapper\n//!BIND MAIN\n//!SAVE MAIN\n"
                        + "vec4 hook() { return MAIN_tex(MAIN_pos) + MAIN_texOff(MAIN_pt); }").get(0);
        Wrapper wrapper = Wrapper.parse(MpvHookShader.fragmentSource(pass), "MAIN");
        double[][] vPosGrid = {{0.0, 0.0}, {0.25, 0.75}, {0.5, 0.5}, {0.9375, 0.0625}, {1.0, 1.0}};
        double[][] offsets = {{-1, -1}, {-1, 0}, {0, 1}, {1, 0}, {1, 1}, {0, -1}};
        double[] size = {16.0, 9.0};
        for (boolean declared : new boolean[]{true, false}) {
            for (boolean inputTopLeft : new boolean[]{true, false}) {
                int flip = Anime4kPostProcessor.flipYUniform(declared, inputTopLeft);
                double worstWrongBinding = 0.0;
                for (double[] vPos : vPosGrid) {
                    assertVec(wrapper.evalPosDefine(vPos, flip),
                            new double[]{vPos[0], refY(vPos[1], declared, inputTopLeft)});
                    for (double[] pos : vPosGrid) {
                        assertVec(wrapper.evalTex(pos, flip),
                                new double[]{pos[0], refY(pos[1], declared, inputTopLeft)});
                    }
                    for (double[] off : offsets) {
                        double[] emitted = wrapper.evalTexOff(vPos, off, size, flip);
                        double[] reference = {vPos[0] + off[0] / size[0],
                                refY(refY(vPos[1], declared, inputTopLeft) + off[1] / size[1], declared, inputTopLeft)};
                        assertVec(emitted, reference);
                        double[] wrong = wrapper.evalTexOff(vPos, off, size, 1 - flip);
                        worstWrongBinding = Math.max(worstWrongBinding, Math.abs(wrong[1] - reference[1]));
                    }
                }
                assertTrue("wrong u_FLIP_Y binding must be distinguishable for declared="
                        + declared + " input=" + inputTopLeft, worstWrongBinding > 0.1);
            }
        }
    }

    @Test public void rejectsWrapperStructuresItCannotEvaluate() {
        MpvHookShader.Pass pass = MpvHookShader.parse(
                "//!DESC wrapper\n//!BIND MAIN\n//!SAVE MAIN\nvec4 hook(){return MAIN_tex(MAIN_pos);}").get(0);
        String source = MpvHookShader.fragmentSource(pass).replace("u_FLIP_Y ?", "u_FLIP_Y @");
        try {
            Wrapper.parse(source, "MAIN");
            fail("wrapper syntax outside the evaluated grammar must fail closed, not pass silently");
        } catch (AssertionError expected) {
            // Structural edits to the wrapper need explicit re-verification of the algebra.
        }
    }

    /** Independent reference: shader-logical y mapped into the texture's physical row order. */
    private static double refY(double logicalY, boolean declaredTopLeft, boolean inputTopLeft) {
        return declaredTopLeft != inputTopLeft ? 1.0 - logicalY : logicalY;
    }

    private static void assertVec(double[] actual, double[] expected) {
        assertEquals(expected.length, actual.length, 0.0);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], 1e-9);
        }
    }

    /** Extracts the emitted hookCoord, _tex, _texOff and _pos definitions for numerical evaluation. */
    private static final class Wrapper {
        final String hookParam;
        final Node hookX, hookY;
        final Node texArg, texOffArg, posDefine;

        private Wrapper(String hookParam, Node hookX, Node hookY, Node texArg, Node texOffArg, Node posDefine) {
            this.hookParam = hookParam;
            this.hookX = hookX;
            this.hookY = hookY;
            this.texArg = texArg;
            this.texOffArg = texOffArg;
            this.posDefine = posDefine;
        }

        static Wrapper parse(String source, String bind) {
            Matcher hook = Pattern.compile("vec2 hookCoord\\(vec2 (\\w+)\\) \\{ return vec2\\(([^,]+),([^;]+)\\); \\}")
                    .matcher(source);
            assertTrue("emitted hookCoord definition not found", hook.find());
            Node hookX = FilterCoordinateWrapperTest.parse(hook.group(2)), hookY = FilterCoordinateWrapperTest.parse(hook.group(3));
            Matcher tex = Pattern.compile("vec4 " + bind + "_tex\\(vec2 pos\\) \\{ return texture\\(u_" + bind + ", ([^;]+)\\); \\}")
                    .matcher(source);
            assertTrue("emitted " + bind + "_tex definition not found", tex.find());
            Matcher texOff = Pattern.compile("vec4 " + bind + "_texOff\\(vec2 off\\) \\{ return " + bind + "_tex\\(([^;]+)\\); \\}")
                    .matcher(source);
            assertTrue("emitted " + bind + "_texOff definition not found", texOff.find());
            Matcher posDef = Pattern.compile("#define " + bind + "_pos ([^\\n]+)").matcher(source);
            assertTrue("emitted " + bind + "_pos define not found", posDef.find());
            return new Wrapper(hook.group(1), hookX, hookY, FilterCoordinateWrapperTest.parse(tex.group(1)),
                    FilterCoordinateWrapperTest.parse(texOff.group(1)), FilterCoordinateWrapperTest.parse(posDef.group(1)));
        }

        double[] evalTex(double[] pos, int flip) {
            return texArg.eval(vars("pos", pos, flip), this);
        }

        double[] evalTexOff(double[] vPos, double[] off, double[] size, int flip) {
            Map<String, double[]> vars = vars("vPos", vPos, flip);
            vars.put("off", off);
            vars.put("u_MAIN_size", size);
            // _texOff(off) is emitted as _tex(<argument>); evaluate the argument, then the _tex body.
            vars.put("pos", texOffArg.eval(vars, this));
            return texArg.eval(vars, this);
        }

        double[] evalPosDefine(double[] vPos, int flip) {
            return posDefine.eval(vars("vPos", vPos, flip), this);
        }

        private static Map<String, double[]> vars(String name, double[] value, int flip) {
            Map<String, double[]> vars = new HashMap<>();
            vars.put(name, value);
            vars.put("u_FLIP_Y", new double[]{flip});
            return vars;
        }
    }

    private interface Node {
        double[] eval(Map<String, double[]> vars, Wrapper wrapper);
    }

    /** Parses the small GLSL expression grammar the emitted wrapper is allowed to use. */
    private static Node parse(String expression) {
        return new Parser(tokenize(expression)).parseTernary();
    }

    private static List<String> tokenize(String expression) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < expression.length()) {
            char c = expression.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isDigit(c)) {
                int j = i;
                while (j < expression.length() && (Character.isDigit(expression.charAt(j)) || expression.charAt(j) == '.')) j++;
                tokens.add(expression.substring(i, j));
                i = j;
            } else if (Character.isJavaIdentifierStart(c)) {
                int j = i;
                while (j < expression.length() && Character.isJavaIdentifierPart(expression.charAt(j))) j++;
                tokens.add(expression.substring(i, j));
                i = j;
            } else if ("+-*/(),?:.".indexOf(c) >= 0) {
                tokens.add(String.valueOf(c));
                i++;
            } else {
                throw new AssertionError("unexpected character in wrapper expression: " + c);
            }
        }
        return tokens;
    }

    private static final class Parser {
        private final List<String> tokens;
        private int pos;

        Parser(List<String> tokens) {
            this.tokens = tokens;
        }

        Node parseTernary() {
            Node condition = parseAdditive();
            if (match("?")) {
                Node whenTrue = parseTernary();
                expect(":");
                Node whenFalse = parseTernary();
                return (vars, wrapper) -> condition.eval(vars, wrapper)[0] != 0
                        ? whenTrue.eval(vars, wrapper) : whenFalse.eval(vars, wrapper);
            }
            return condition;
        }

        private Node parseAdditive() {
            Node left = parseMultiplicative();
            while (match("+") || match("-")) {
                String op = tokens.get(pos - 1);
                Node lhs = left;
                Node rhs = parseMultiplicative();
                left = (vars, wrapper) -> componentwise(op, lhs.eval(vars, wrapper), rhs.eval(vars, wrapper));
            }
            return left;
        }

        private Node parseMultiplicative() {
            Node left = parseUnary();
            while (match("*") || match("/")) {
                String op = tokens.get(pos - 1);
                Node lhs = left;
                Node rhs = parseUnary();
                left = (vars, wrapper) -> componentwise(op, lhs.eval(vars, wrapper), rhs.eval(vars, wrapper));
            }
            return left;
        }

        private Node parseUnary() {
            if (match("-")) {
                Node inner = parseUnary();
                return (vars, wrapper) -> {
                    double[] v = inner.eval(vars, wrapper);
                    double[] out = new double[v.length];
                    for (int i = 0; i < v.length; i++) out[i] = -v[i];
                    return out;
                };
            }
            return parsePostfix();
        }

        private Node parsePostfix() {
            Node primary = parsePrimary();
            while (match(".")) {
                String field = expectIdent();
                Node base = primary;
                primary = (vars, wrapper) -> {
                    double[] v = base.eval(vars, wrapper);
                    if ("x".equals(field)) return new double[]{v[0]};
                    if ("y".equals(field)) return new double[]{v[1]};
                    throw new AssertionError("unsupported swizzle: " + field);
                };
            }
            return primary;
        }

        private Node parsePrimary() {
            String token = peek();
            if (token == null) throw new AssertionError("unexpected end of wrapper expression");
            if (Character.isDigit(token.charAt(0))) {
                pos++;
                double value = Double.parseDouble(token);
                return (vars, wrapper) -> new double[]{value};
            }
            if (Character.isJavaIdentifierStart(token.charAt(0))) {
                pos++;
                if ("(".equals(peek())) {
                    pos++;
                    List<Node> args = new ArrayList<>();
                    if (!")".equals(peek())) {
                        args.add(parseTernary());
                        while (match(",")) args.add(parseTernary());
                    }
                    expect(")");
                    String name = token;
                    return (vars, wrapper) -> {
                        if ("hookCoord".equals(name)) {
                            double[] arg = args.get(0).eval(vars, wrapper);
                            Map<String, double[]> scope = new HashMap<>(vars);
                            scope.put(wrapper.hookParam, arg);
                            return new double[]{wrapper.hookX.eval(scope, wrapper)[0],
                                    wrapper.hookY.eval(scope, wrapper)[0]};
                        }
                        if ("vec2".equals(name)) {
                            if (args.size() != 2) throw new AssertionError("vec2 expects two components");
                            return new double[]{args.get(0).eval(vars, wrapper)[0], args.get(1).eval(vars, wrapper)[0]};
                        }
                        throw new AssertionError("unexpected call in wrapper expression: " + name);
                    };
                }
                String name = token;
                return (vars, wrapper) -> {
                    double[] value = vars.get(name);
                    if (value == null) throw new AssertionError("unbound variable in wrapper expression: " + name);
                    return value;
                };
            }
            if ("(".equals(token)) {
                pos++;
                Node inner = parseTernary();
                expect(")");
                return inner;
            }
            throw new AssertionError("unexpected token in wrapper expression: " + token);
        }

        private boolean match(String token) {
            if (token.equals(peek())) {
                pos++;
                return true;
            }
            return false;
        }

        private String peek() {
            return pos < tokens.size() ? tokens.get(pos) : null;
        }

        private String expectIdent() {
            String token = peek();
            if (token == null || !Character.isJavaIdentifierStart(token.charAt(0))) {
                throw new AssertionError("expected identifier, got: " + token);
            }
            pos++;
            return token;
        }

        private void expect(String token) {
            if (!match(token)) throw new AssertionError("expected " + token + " at token " + pos);
        }

        private static double[] componentwise(String op, double[] left, double[] right) {
            double[] out = new double[Math.max(left.length, right.length)];
            for (int i = 0; i < out.length; i++) {
                double a = left.length == 1 ? left[0] : left[i];
                double b = right.length == 1 ? right[0] : right[i];
                switch (op) {
                    case "+": out[i] = a + b; break;
                    case "-": out[i] = a - b; break;
                    case "*": out[i] = a * b; break;
                    case "/": out[i] = a / b; break;
                    default: throw new AssertionError("unsupported operator: " + op);
                }
            }
            return out;
        }
    }
}

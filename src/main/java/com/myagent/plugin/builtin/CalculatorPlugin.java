package com.myagent.plugin.builtin;

import com.myagent.plugin.AgentPlugin;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 内置插件「计算器」:四则运算交给确定性求值,避免模型心算出错。
 * 求值器是自写递归下降(+ - * / 与括号、一元正负),不引入表达式库依赖。
 */
@Component
public class CalculatorPlugin implements AgentPlugin {

    private static final int MAX_EXPR_LEN = 200;

    @Override
    public String key() {
        return "plugin-calculator";
    }

    @Override
    public String title() {
        return "计算器";
    }

    @Override
    public String description() {
        return "四则运算表达式求值(加 / 减 / 乘 / 除、括号、小数),数字计算一律走它,不要心算。";
    }

    @Override
    public List<Object> toolBeans(Map<String, Object> config) {
        return List.of(new Tools());
    }

    static class Tools {

        @Tool(description = "计算四则运算表达式并返回结果。支持 + - * /、括号与小数,如 (3.5+1.5)*2")
        public String calculate(
                @ToolParam(description = "算术表达式,仅含数字与 + - * / ( )") String expression) {
            String expr = expression == null ? "" : expression.trim();
            if (expr.isEmpty()) {
                return "错误:表达式为空";
            }
            if (expr.length() > MAX_EXPR_LEN) {
                return "错误:表达式过长(最多 " + MAX_EXPR_LEN + " 字符)";
            }
            try {
                return expr + " = " + new Parser(expr).parse();
            } catch (ArithmeticException e) {
                return "错误:" + e.getMessage();
            } catch (Exception e) {
                return "错误:无法解析的表达式(" + e.getMessage() + ")";
            }
        }
    }

    /** 递归下降:expr = term (('+'|'-') term)*;term = factor (('*'|'/') factor)*;factor = 数 | '(' expr ')' | 一元正负。 */
    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        double parse() {
            double v = expr();
            skipWs();
            if (i < s.length()) {
                throw new IllegalArgumentException("位置 " + (i + 1) + " 有多余字符");
            }
            return v;
        }

        private double expr() {
            double v = term();
            while (true) {
                skipWs();
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                    char op = s.charAt(i++);
                    v = op == '+' ? v + term() : v - term();
                } else {
                    return v;
                }
            }
        }

        private double term() {
            double v = factor();
            while (true) {
                skipWs();
                if (i < s.length() && (s.charAt(i) == '*' || s.charAt(i) == '/')) {
                    char op = s.charAt(i++);
                    double rhs = factor();
                    if (op == '/' && rhs == 0) {
                        throw new ArithmeticException("除以零");
                    }
                    v = op == '*' ? v * rhs : v / rhs;
                } else {
                    return v;
                }
            }
        }

        private double factor() {
            skipWs();
            if (i >= s.length()) {
                throw new IllegalArgumentException("表达式意外结束");
            }
            char c = s.charAt(i);
            if (c == '+') {
                i++;
                return factor();
            }
            if (c == '-') {
                i++;
                return -factor();
            }
            if (c == '(') {
                i++;
                double v = expr();
                skipWs();
                if (i >= s.length() || s.charAt(i) != ')') {
                    throw new IllegalArgumentException("缺少右括号");
                }
                i++;
                return v;
            }
            return number();
        }

        private double number() {
            int start = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                i++;
            }
            if (start == i) {
                throw new IllegalArgumentException("位置 " + (i + 1) + " 不是数字");
            }
            return Double.parseDouble(s.substring(start, i));
        }

        private void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }
    }
}

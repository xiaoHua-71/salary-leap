package com.xiaohua.service.eval;

import java.util.Locale;

/**
 * 来源匹配：判断「检索回来的片段来自哪篇文章」是不是评估集里期望的那篇。
 *
 * <p>评估集里的期望来源故意写成<b>路径片段</b>（{@code database/mysql/mysql-index.html}）
 * 而不是完整 URL，好处有三：</p>
 * <ul>
 *   <li>写得短，人改起来不容易错</li>
 *   <li>换域名（比如以后换成自己的站）评估集不用动</li>
 *   <li>内置知识文件（{@code knowledge/java-backend.txt}）本来就没有域名，天然适用同一套规则</li>
 * </ul>
 *
 * <p>匹配前先归一化两边：去协议、去 {@code www.}、去 {@code #} 锚点、去结尾斜杠、统一小写。
 * <b>保留 {@code ?} 查询串</b> —— 库里确实有带查询串的来源
 * （{@code developer.baidu.com/article/detail.html?id=4647986}），
 * 去掉的话这个路径会变成通配，任何同类页面都能匹配上。</p>
 *
 * <p>比较用「相等 或 以 {@code /} 为边界互相结尾」，那个斜杠是关键：
 * 不然 {@code proxy.html} 会误配 {@code dynamic-proxy.html}。</p>
 */
public final class EvalSourceMatcher {

    private EvalSourceMatcher() {
    }

    /**
     * 归一化：小写、去协议、去 {@code www.}、去锚点、去结尾斜杠。
     *
     * @return 归一化后的字符串；入参为 null 时返回空串
     */
    public static String normalize(String source) {
        if (source == null) {
            return "";
        }
        String s = source.trim().toLowerCase(Locale.ROOT);
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        }
        int fragment = s.indexOf('#');
        if (fragment >= 0) {
            s = s.substring(0, fragment);
        }
        if (s.startsWith("www.")) {
            s = s.substring(4);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * 判断实际来源是否命中某个期望来源。
     *
     * @param expected 评估集里写的期望来源（路径片段或完整 URL）
     * @param actual   检索回来片段携带的来源（{@code url} 或 {@code file_name}）
     * @return 命中返回 true；任一侧为空返回 false
     */
    public static boolean matches(String expected, String actual) {
        String e = normalize(expected);
        String a = normalize(actual);
        if (e.isEmpty() || a.isEmpty()) {
            return false;
        }
        return a.equals(e)
                || a.endsWith("/" + e)
                || e.endsWith("/" + a);
    }
}

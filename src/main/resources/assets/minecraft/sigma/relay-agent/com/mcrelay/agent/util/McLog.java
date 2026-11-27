package com.mcrelay.agent.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 极简日志器：写到 MC 运行目录下的 mc-relay-agent.log，同时 println。
 * 使用 UTF-8 编码，确保中文不乱码。
 */
public final class McLog {
    private static final File LOG_FILE = new File("mc-relay-agent.log");
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS");

    /** 每个 JVM（每次注入）第一次写日志时清空文件，避免无限增长。 */
    private static volatile boolean truncated = false;

    private McLog() {}

    public static void info(String msg) { write("INFO ", msg); }
    public static void warn(String msg) { write("WARN ", msg); }
    public static void error(String msg, Throwable t) {
        write("ERROR", msg);
        if (t != null) {
            try (PrintWriter pw = open()) {
                t.printStackTrace(pw);
            } catch (Exception ignored) {}
            t.printStackTrace();
        }
    }
    public static void error(String msg) { write("ERROR", msg); }

    private static synchronized void write(String lvl, String msg) {
        String line = "[" + TS.format(new Date()) + "] [" + lvl + "] " + msg;
        System.out.println(line);
        try (PrintWriter pw = open()) {
            pw.println(line);
        } catch (Exception ignored) {}
    }

    /** 首次调用用覆盖模式打开（清空旧内容），之后都是追加。 */
    private static PrintWriter open() throws Exception {
        boolean append = truncated;
        truncated = true;
        return new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(LOG_FILE, append), java.nio.charset.StandardCharsets.UTF_8));
    }
}

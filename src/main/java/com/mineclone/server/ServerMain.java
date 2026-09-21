package com.mineclone.server;

import java.io.File;

/**
 * Точка входа выделенного сервера.
 *
 * <pre>java -cp "out;libs/*" com.mineclone.server.ServerMain [server.properties]</pre>
 *
 * <p>Отдельная от {@link com.mineclone.Main} нарочно: та первым делом заводит
 * окно GLFW и контекст OpenGL, а на машине без экрана это падение ещё до
 * первой полезной строчки. Здесь же нет ни одного импорта из {@code render} и
 * {@code audio} — и это проверяется тестом, а не обещанием.
 *
 * <p>Ctrl+C ловится и доводится до конца: мир обязан быть записан, а комната —
 * закрыта. Оставить это операционной системе значит время от времени терять
 * последнюю минуту игры.
 */
public final class ServerMain {

    private ServerMain() {
    }

    public static void main(String[] args) {
        // Вывод в UTF-8: свои строки сервер пишет латиницей (у консоли Windows
        // кодировка не UTF-8), но через него проходят и сообщения сессии —
        // те по-русски, и без этого они превратились бы в кашу.
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(
                java.io.FileDescriptor.out), true, java.nio.charset.StandardCharsets.UTF_8));
        System.setErr(new java.io.PrintStream(new java.io.FileOutputStream(
                java.io.FileDescriptor.err), true, java.nio.charset.StandardCharsets.UTF_8));
        File file = new File(args.length > 0 && !args[0].isBlank()
                ? args[0] : ServerConfig.DEFAULT_FILE);
        ServerConfig config = ServerConfig.load(file);
        DedicatedServer server = new DedicatedServer(config);

        // Ctrl+C: цикл доигрывает тик и выходит через обычный путь, тот же,
        // что у /stop. Второй Ctrl+C всё равно убьёт процесс — это поведение
        // виртуальной машины, и ломать его не надо.
        Thread stopper = new Thread(() -> server.stop("signal"), "mineclone-shutdown");
        Runtime.getRuntime().addShutdownHook(stopper);

        new ServerConsole(server).start();
        try {
            server.run();
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(stopper);
            } catch (IllegalStateException alreadyShuttingDown) {
                // Пришли сюда по сигналу — крючок снимать поздно и незачем.
            }
        }
    }
}

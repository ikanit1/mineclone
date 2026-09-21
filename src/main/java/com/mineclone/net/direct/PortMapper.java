package com.mineclone.net.direct;

import java.util.function.Consumer;

/**
 * Открыть порт на роутере, не открывая его настройки.
 *
 * <p>Прямое соединение до этой работы было честно бесполезно за пределами
 * своей квартиры: {@link com.mineclone.net.LanTransport} слушал порт, а за NAT
 * к нему никто не приходил. Пробросить порт руками умеет не всякий, у кого
 * есть друг и желание построить с ним дом.
 *
 * <p>Способов два, и пробуются оба: {@link UpnpGateway} (его понимают почти
 * все кабельные роутеры) и {@link NatPmp} (короче, старше, живёт у Apple и у
 * части прошивок). Порядок такой, потому что UPnP заодно называет внешний
 * адрес — а его всё равно придётся узнать.
 *
 * <p><b>Это попытка, а не гарантия.</b> У многих роутеров UPnP выключен из
 * соображений безопасности, у операторских сетей внешнего адреса нет вовсе.
 * Такой исход не поломка: игра говорит об этом прямо и предлагает облако.
 */
public final class PortMapper implements AutoCloseable {

    /** Чем удалось открыть порт. */
    public enum Method {
        /** Не удалось ничем. */
        NONE,
        UPNP,
        NAT_PMP
    }

    /** Итог: чем открыли, как нас видно снаружи и почему не вышло. */
    public record Result(Method method, String externalAddress, int port, String error) {

        public boolean ok() {
            return method != Method.NONE;
        }

        /** Адрес, который хозяин диктует другу. */
        public String dialable() {
            if (externalAddress.isEmpty())
                return "";
            return externalAddress + ":" + port;
        }
    }

    private Method method = Method.NONE;
    private UpnpGateway.Control control;
    private int port;

    /**
     * Открыть порт.
     *
     * <p>Блокирует на несколько секунд — рассылка, загрузка описания,
     * SOAP-запрос. Звать из фонового потока; для этого есть {@link #openAsync}.
     */
    public Result open(int port, String description) {
        this.port = port;
        String upnpError = tryUpnp(port, description);
        if (method == Method.UPNP)
            return new Result(Method.UPNP, externalAddress(), port, "");
        String pmpError = tryNatPmp(port);
        if (method == Method.NAT_PMP)
            return new Result(Method.NAT_PMP, externalAddress(), port, "");
        return new Result(Method.NONE, PublicAddress.discover(), port,
                combine(upnpError, pmpError));
    }

    /** То же самое, но в своём потоке: ответ приезжает в {@code done}. */
    public void openAsync(int port, String description, Consumer<Result> done) {
        Thread t = new Thread(() -> done.accept(open(port, description)),
                "mineclone-port-mapper");
        t.setDaemon(true);
        t.start();
    }

    private String tryUpnp(int port, String description) {
        UpnpGateway.Control found = UpnpGateway.discover();
        if (found == null)
            return "роутер не отозвался на UPnP";
        String local = PublicAddress.localAddress();
        if (local.isEmpty())
            return "не удалось определить свой адрес в сети";
        String error = UpnpGateway.addMapping(found, port, description, local);
        if (error.isEmpty()) {
            control = found;
            method = Method.UPNP;
            return "";
        }
        return error;
    }

    private String tryNatPmp(int port) {
        NatPmp.Mapping m = NatPmp.open(port);
        if (m.ok()) {
            method = Method.NAT_PMP;
            return "";
        }
        return m.error();
    }

    private String externalAddress() {
        if (control != null) {
            String fromRouter = UpnpGateway.externalAddress(control);
            if (!fromRouter.isEmpty())
                return fromRouter;
        }
        return PublicAddress.discover();
    }

    /**
     * Убрать отображение.
     *
     * <p>Вежливость, а не необходимость: срок жизни у отображения конечный, и
     * забытое само исчезнет через час. Но роутер с полусотней забытых
     * отображений — это тот роутер, который в следующий раз откажет.
     */
    @Override
    public void close() {
        Method m = method;
        method = Method.NONE;
        switch (m) {
            case UPNP -> {
                UpnpGateway.Control c = control;
                control = null;
                if (c != null)
                    UpnpGateway.removeMapping(c, port);
            }
            case NAT_PMP -> NatPmp.close(port);
            case NONE -> {
                // Нечего убирать.
            }
        }
    }

    public Method method() {
        return method;
    }

    /** Два отказа в одну строку: игроку важно, что не вышло ни так, ни так. */
    public static String combine(String upnp, String natPmp) {
        if (upnp.isEmpty() && natPmp.isEmpty())
            return "порт открыть не удалось";
        if (upnp.equals(natPmp))
            return upnp;
        if (upnp.isEmpty())
            return natPmp;
        if (natPmp.isEmpty())
            return upnp;
        return "UPnP: " + upnp + "; NAT-PMP: " + natPmp;
    }
}

package com.mineclone.net.photon;

/**
 * Числа протокола Photon Realtime.
 *
 * <p>Взяты один в один из официального клиента (пакет {@code photon-realtime},
 * версия 4.4.0) — это единственный публично читаемый источник: Java-SDK у
 * Photon нет вообще, а в остальных SDK те же таблицы лежат в скомпилированном
 * виде. Менять их нельзя: числа задаёт сервер.
 *
 * <p>Часть кодов исторически называется «Lite»: это общий слой сервера, на
 * котором стоит матчмейкинг. Поэтому {@code Leave} и {@code RaiseEvent} лежат
 * в другом диапазоне, чем {@code Authenticate} и {@code JoinGame}.
 */
public final class PhotonCodes {

    private PhotonCodes() {
    }

    /** Версия клиента в строке запроса — сервер по ней выбирает поведение. */
    public static final String LIB_VERSION = "4.4.0.0";
    /** Имя подпротокола WebSocket: значения едут обычным JSON, а не GpBinaryV16. */
    public static final String SUBPROTOCOL = "Json";
    /** Сервер имён, шифрованный канал. */
    public static final String NAME_SERVER_WSS = "wss://ns.photonengine.io:19093";
    /** Сервер имён без шифрования — запасной путь, если TLS не проходит. */
    public static final String NAME_SERVER_WS = "ws://ns.photonengine.io:9093";

    // ------------------------------------------------------------- операции

    public static final int OP_AUTHENTICATE = 230;
    public static final int OP_JOIN_LOBBY = 229;
    public static final int OP_LEAVE_LOBBY = 228;
    public static final int OP_CREATE_GAME = 227;
    public static final int OP_JOIN_GAME = 226;
    public static final int OP_JOIN_RANDOM_GAME = 225;
    public static final int OP_GET_REGIONS = 220;
    public static final int OP_LEAVE = 254;
    public static final int OP_RAISE_EVENT = 253;
    public static final int OP_SET_PROPERTIES = 252;
    public static final int OP_GET_PROPERTIES = 251;

    // -------------------------------------------------------------- события

    public static final int EV_GAME_LIST = 230;
    public static final int EV_GAME_LIST_UPDATE = 229;
    public static final int EV_APP_STATS = 226;
    public static final int EV_LOBBY_STATS = 224;
    public static final int EV_AUTH = 223;
    public static final int EV_ERROR_INFO = 251;
    public static final int EV_DISCONNECT = 252;
    public static final int EV_PROPERTIES_CHANGED = 253;
    public static final int EV_LEAVE = 254;
    public static final int EV_JOIN = 255;

    // ------------------------------------------------------------ параметры

    public static final int P_ADDRESS = 230;
    public static final int P_PEER_COUNT = 229;
    public static final int P_GAME_COUNT = 228;
    public static final int P_USER_ID = 225;
    public static final int P_APPLICATION_ID = 224;
    public static final int P_GAME_LIST = 222;
    public static final int P_SECRET = 221;
    public static final int P_APP_VERSION = 220;
    public static final int P_REGION = 210;
    public static final int P_LOBBY_NAME = 213;
    public static final int P_LOBBY_TYPE = 212;
    public static final int P_JOIN_MODE = 215;
    public static final int P_MASTER_CLIENT_ID = 203;
    public static final int P_PLUGINS = 204;
    public static final int P_NICKNAME = 202;
    public static final int P_PUBLISH_USER_ID = 239;
    public static final int P_INFO = 218;
    public static final int P_CLEANUP_CACHE_ON_LEAVE = 241;
    public static final int P_CHECK_USER_ON_JOIN = 232;
    public static final int P_IS_INACTIVE = 233;
    public static final int P_PLAYER_TTL = 235;
    public static final int P_EMPTY_ROOM_TTL = 236;

    // Общий слой сервера: ключи параметров с «верхнего» конца диапазона.
    public static final int P_GAME_ID = 255;
    public static final int P_ACTOR_NR = 254;
    public static final int P_TARGET_ACTOR_NR = 253;
    public static final int P_ACTOR_LIST = 252;
    public static final int P_PROPERTIES = 251;
    public static final int P_BROADCAST = 250;
    public static final int P_ACTOR_PROPERTIES = 249;
    public static final int P_GAME_PROPERTIES = 248;
    public static final int P_CACHE = 247;
    public static final int P_RECEIVER_GROUP = 246;
    public static final int P_DATA = 245;
    public static final int P_CODE = 244;
    public static final int P_GROUP = 240;
    public static final int P_ADD = 238;

    // ------------------------------------------------- свойства комнаты и игрока

    public static final int ROOM_MAX_PLAYERS = 255;
    public static final int ROOM_IS_VISIBLE = 254;
    public static final int ROOM_IS_OPEN = 253;
    public static final int ROOM_PLAYER_COUNT = 252;
    public static final int ROOM_REMOVED = 251;
    public static final int ROOM_PROPS_IN_LOBBY = 250;
    public static final int ROOM_MASTER_CLIENT_ID = 248;
    public static final int ACTOR_PLAYER_NAME = 255;
    public static final int ACTOR_USER_ID = 253;

    /** Войти, а если комнаты нет — создать. */
    public static final int JOIN_MODE_CREATE_IF_NOT_EXISTS = 1;

    /** Получатели события: всем, кроме себя. */
    public static final int RECEIVER_OTHERS = 0;
    /** Получатели события: всем, включая себя. */
    public static final int RECEIVER_ALL = 1;

    // --------------------------------------------------------------- ошибки

    public static final int ERR_OK = 0;
    public static final int ERR_INVALID_AUTHENTICATION = 32767;
    public static final int ERR_GAME_ID_ALREADY_EXISTS = 32766;
    public static final int ERR_GAME_FULL = 32765;
    public static final int ERR_GAME_CLOSED = 32764;
    public static final int ERR_SERVER_FULL = 32762;
    public static final int ERR_GAME_DOES_NOT_EXIST = 32758;
    public static final int ERR_MAX_CCU_REACHED = 32757;
    public static final int ERR_INVALID_REGION = 32756;
    public static final int ERR_CUSTOM_AUTH_FAILED = 32755;
    public static final int ERR_OPERATION_LIMIT_REACHED = 32743;

    /** Ошибка сервера по-русски: игроку важно понять, что делать дальше. */
    public static String errorText(int code, String serverMessage) {
        String known = switch (code) {
            case ERR_INVALID_AUTHENTICATION -> "Photon не принял ключ приложения";
            case ERR_GAME_ID_ALREADY_EXISTS -> "комната с таким именем уже есть";
            case ERR_GAME_FULL -> "комната заполнена";
            case ERR_GAME_CLOSED -> "комната закрыта";
            case ERR_SERVER_FULL -> "сервер Photon перегружен";
            case ERR_GAME_DOES_NOT_EXIST -> "такой комнаты нет";
            case ERR_MAX_CCU_REACHED -> "исчерпан лимит игроков вашего приложения Photon";
            case ERR_INVALID_REGION -> "неизвестный регион";
            case ERR_CUSTOM_AUTH_FAILED -> "внешняя проверка входа не прошла";
            case ERR_OPERATION_LIMIT_REACHED -> "слишком часто: Photon ограничил запросы";
            default -> null;
        };
        if (known == null)
            return (serverMessage == null || serverMessage.isEmpty())
                    ? "ошибка Photon " + code
                    : serverMessage + " (" + code + ")";
        return known;
    }

    /** Регионы облака Photon. Пустая строка — сервер имён выберет лучший сам. */
    public static final String[] REGIONS = {
            "", "eu", "us", "usw", "asia", "jp", "au", "sa", "kr", "in", "ru", "cae", "za"
    };

    /** Подпись региона для экрана. */
    public static String regionLabel(String region) {
        return switch (region) {
            case "" -> "Авто";
            case "eu" -> "Европа";
            case "us" -> "США, восток";
            case "usw" -> "США, запад";
            case "asia" -> "Азия";
            case "jp" -> "Япония";
            case "au" -> "Австралия";
            case "sa" -> "Южная Америка";
            case "kr" -> "Корея";
            case "in" -> "Индия";
            case "ru" -> "Россия";
            case "cae" -> "Канада";
            case "za" -> "Южная Африка";
            default -> region;
        };
    }
}

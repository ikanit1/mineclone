package com.mineclone.net;

/**
 * Коды пакетов и версия протокола.
 *
 * <p>Пакет — это байт кода и его тело; несколько пакетов уезжают в одном
 * сообщении транспорта ({@link NetChannel}). Так сделано не ради экономии
 * байтов, а ради лимита сообщений: у Photon комната считает сообщения, а не
 * килобайты, и один кадр сети обязан стоить одно сообщение, сколько бы правок
 * блоков и мобов в него ни попало.
 *
 * <p>Кто кому пишет, зашито в имени: {@code S_} — хозяин комнате, {@code C_} —
 * участник хозяину, {@code X_} — в обе стороны. Модель власти хозяйская: мир
 * живёт у хозяина комнаты, участники просят, а не решают. ADR:
 * {@code knowledge/decisions/multiplayer-photon.md}.
 */
public final class NetProto {

    private NetProto() {
    }

    /**
     * Версия протокола. Сверяется при входе: чужая — отказ с внятным текстом,
     * а не тихий мир, в котором половина блоков не там.
     *
     * <p>v5: selected item appearance with reliable equipment updates.
     *
     * <p>v4: complete mob animation snapshots and monotonic batch sequence.
     *
     * <p>v3: у прямого соединения появился кадр «я ещё здесь»
     * ({@code F_PING}), и сборка v2, получив его, сочла бы поток испорченным и
     * молча оборвала связь. Она же отделяет объявления маяка локальной сети:
     * мир чужой версии не должен попадать в список, где по нему можно щёлкнуть.
     */
    // v7: persistent player identities/checkpoints and host-executed inventory gestures.
    /**
     * v8 (the 1.1 cycle, NET-02): raised once, at the first incompatible change,
     * and not again before the release — later 1.1 changes to v8 bodies land
     * under the same number. So far: {@code S_PLAYER_HURT} carries the kind of
     * harm, its dealer, origin and knockback ({@link PlayerHurt}); {@code
     * S_WELCOME} the host's generator and precise clock ({@link Welcome}), after
     * {@code S_GEN_MAP} with the chunks pinned to another version ({@link
     * GenMap}); {@code S_CHUNK_DELTA} the version its delta was taken against.
     * The table of every code is {@code docs/NETWORK_PROTOCOL.md}; a test holds
     * it to this file.
     */
    public static final int VERSION = 8;

    /** Сколько раз в секунду уходит кадр сети. */
    public static final float TICK_RATE = 12f;
    /** Как часто хозяин рассылает время суток: оно и так течёт у всех одинаково. */
    public static final float TIME_SYNC_INTERVAL = 5f;
    /** Сколько знаков помещается в одно сообщение чата. */
    public static final int CHAT_LIMIT = 160;
    /** Предельная длина имени игрока. */
    public static final int NAME_LIMIT = 20;

    // -------------------------------------------------------- рукопожатие

    /** Участник представляется: версия протокола, имя и постоянный UUID. */
    public static final int C_HELLO = 1;
    /** Хозяин отвечает: сид, имя мира, время, режим — всё, из чего мир рождается. */
    public static final int S_WELCOME = 2;
    /** Хозяин отказывает: причина текстом. */
    public static final int S_REJECT = 3;

    // -------------------------------------------------------------- игроки

    /** Положение, курс, состояние — самый частый пакет, шлётся всеми. */
    public static final int X_PLAYER_STATE = 10;
    /** Имя, режим и здоровье: редко, но надёжно. */
    public static final int X_PLAYER_INFO = 11;
    /** Игрок умер или возродился — чтобы модель не стояла трупом в воздухе. */
    public static final int X_PLAYER_LIFE = 12;
    /** Замах рукой: удар виден соседям. */
    public static final int X_PLAYER_SWING = 13;
    /** Игрок поставил или сломал блок: отдельное событие для пространственного звука. */
    public static final int X_BLOCK_ACTION = 14;
    /** Selected item appearance, including an empty hand. */
    public static final int X_PLAYER_EQUIPMENT = 15;

    // ----------------------------------------------------------------- мир

    /** Хозяин: блок стал таким. Признак разрушения — играть звук и пыль. */
    public static final int S_BLOCK_SET = 20;
    /** Участник просит поставить или сломать блок. */
    public static final int C_BLOCK_EDIT = 21;
    /** Участник просит дельту чанка. */
    public static final int C_CHUNK_REQUEST = 22;
    /** Хозяин отдаёт дельту: чем чанк отличается от свежесгенерированного. */
    public static final int S_CHUNK_DELTA = 23;
    /** Время суток. */
    public static final int S_TIME = 24;

    // ------------------------------------------------------------- существа

    /**
     * Полный снимок мобов хозяина для всех участников, не события «появился/исчез».
     *
     * <p>Мобов в мире два десятка, и полный список стоит дешевле, чем
     * отдельные события на каждого: у списка нет состояния, которое могло бы
     * разойтись, и потерянный пакет исправляется следующим.
     */
    public static final int S_MOBS = 30;
    /** Снимок предметов на земле — тоже список целиком. */
    public static final int S_ITEMS = 32;
    /** Участник ударил моба. */
    public static final int C_MOB_HIT = 34;

    /** Хозяин рассылает летящие и воткнувшиеся снаряды. */
    public static final int S_PROJECTILES = 35;
    /** Участник просит хозяина выпустить снаряд: стреляет всегда хозяин. */
    public static final int C_SHOOT = 36;
    /**
     * Хозяин сообщает участнику, что тот получил урон.
     *
     * До этого пакета мобы не могли ранить участника вовсе: урон считался у
     * хозяина, а сказать о нём было нечем.
     */
    public static final int S_PLAYER_HURT = 37;

    // ---------------------------------------------------------- контейнеры

    /** Retired in v7; reserved so old full-container packets cannot be reinterpreted. */
    public static final int C_CONTAINER_OPEN = 40;
    /** Retired in v7. */
    public static final int S_CONTAINER = 41;
    /** Retired in v7: clients may never replace a whole shared container. */
    public static final int C_CONTAINER_COMMIT = 42;

    /** Client checkpoint and the host's restored checkpoint on entry. */
    public static final int C_PLAYER = 60, S_PLAYER = 61;
    /** Open, execute one gesture, close, and authoritative menu response. */
    public static final int C_OPEN = 62, C_ACTION = 63, C_CLOSE = 64, S_MENU = 65;
    /** Dropping and picking up carry a checkpoint with the ownership change. */
    public static final int C_DROP = 66, S_DROP_ACK = 67, C_PICKUP = 68, S_PICKUP = 69;

    // -------------------------------------------------------------- прочее

    /** Строка чата или служебное сообщение. */
    public static final int X_CHAT = 50;
    /** Retired in v7; replaced by C_PICKUP with a player checkpoint. */
    public static final int C_ITEM_PICK = 51;
    /** Хозяин выдаёт участнику стопку — подобранное или выбитое. */
    public static final int S_GIVE = 52;

    // ----------------------------------------------------------------- v8

    /**
     * A guest uses a block (BLK-03): {@code blockPos, u8 face, u8 hitX, hitY,
     * hitZ, varInt sequence}. The host runs the block's behaviour itself; what
     * it changes comes back as {@code S_BLOCK_SET}.
     */
    public static final int C_USE_BLOCK = 70;
    /** Chunks the host's world generates with another version than its own ({@link GenMap}); before S_WELCOME. */
    public static final int S_GEN_MAP = 74;

    /** Читаемое имя кода — только для отладочной строки и сообщений об ошибке. */
    public static String name(int code) {
        return switch (code) {
            case C_HELLO -> "HELLO";
            case S_WELCOME -> "WELCOME";
            case S_REJECT -> "REJECT";
            case X_PLAYER_STATE -> "PLAYER_STATE";
            case X_PLAYER_INFO -> "PLAYER_INFO";
            case X_PLAYER_LIFE -> "PLAYER_LIFE";
            case X_PLAYER_SWING -> "PLAYER_SWING";
            case X_BLOCK_ACTION -> "BLOCK_ACTION";
            case X_PLAYER_EQUIPMENT -> "PLAYER_EQUIPMENT";
            case S_BLOCK_SET -> "BLOCK_SET";
            case C_BLOCK_EDIT -> "BLOCK_EDIT";
            case C_CHUNK_REQUEST -> "CHUNK_REQUEST";
            case S_CHUNK_DELTA -> "CHUNK_DELTA";
            case S_TIME -> "TIME";
            case S_MOBS -> "MOBS";
            case S_ITEMS -> "ITEMS";
            case C_MOB_HIT -> "MOB_HIT";
            case S_PROJECTILES -> "PROJECTILES";
            case C_SHOOT -> "SHOOT";
            case S_PLAYER_HURT -> "PLAYER_HURT";
            case C_CONTAINER_OPEN -> "CONTAINER_OPEN";
            case S_CONTAINER -> "CONTAINER";
            case C_CONTAINER_COMMIT -> "CONTAINER_COMMIT";
            case C_PLAYER -> "PLAYER_CHECKPOINT";
            case S_PLAYER -> "PLAYER_RESTORE";
            case C_OPEN -> "MENU_OPEN";
            case C_ACTION -> "MENU_ACTION";
            case C_CLOSE -> "MENU_CLOSE";
            case S_MENU -> "MENU_STATE";
            case C_DROP -> "ITEM_DROP";
            case S_DROP_ACK -> "ITEM_DROP_ACK";
            case C_PICKUP -> "ITEM_PICKUP";
            case S_PICKUP -> "ITEM_PICKUP_ACK";
            case X_CHAT -> "CHAT";
            case C_ITEM_PICK -> "ITEM_PICK";
            case S_GIVE -> "GIVE";
            case C_USE_BLOCK -> "USE_BLOCK";
            case S_GEN_MAP -> "GEN_MAP";
            default -> "code" + code;
        };
    }
}

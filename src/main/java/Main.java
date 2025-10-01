import java.util.*;

// ===================== МОДЕЛИ ДЛЯ СОХРАНЕНИЙ =====================
class SaveGame {
    Warrior[] teamA;
    Warrior[] teamB;
    int round;
    int logLevel;
    boolean color;

    // v2: метаданные слота
    String saveName;
    long savedAtEpochMillis;

    SaveGame() {}
    SaveGame(Warrior[] teamA, Warrior[] teamB, int round, int logLevel, boolean color) {
        this.teamA = teamA;
        this.teamB = teamB;
        this.round = round;
        this.logLevel = logLevel;
        this.color = color;
    }
}

class SaveMeta {
    String id;         // save-001
    String saveName;   // имя слота
    long savedAt;      // millis
    String path;       // путь к json-файлу

    SaveMeta() {}
    SaveMeta(String id, String saveName, long savedAt, String path) {
        this.id = id; this.saveName = saveName; this.savedAt = savedAt; this.path = path;
    }
}

// ===================== КАМПАНИЯ: СОСТОЯНИЕ =====================
class CampaignState {
    int day = 1;
    int gold = 100;
    int difficulty = 1; // 1-легко, 2-норма, 3-сложно
    // максимум 5 бойцов в активном отряде
    Warrior[] roster = new Warrior[5];

    int aliveCount() {
        int c = 0;
        for (Warrior w : roster) if (w != null && w.hp > 0) c++;
        return c;
    }
}

// ===================== РОЛИ =====================
enum Role {
    NONE, TANK, DUELIST, SKIRMISHER, SUPPORT;

    void applyTo(Warrior w) {
        switch (this) {
            case NONE: break;
            case TANK:
                w.armor += 1;
                w.blockChance = clamp01(w.blockChance + 0.05);
                w.missChance  = clamp01(w.missChance  - 0.02);
                break;
            case DUELIST:
                w.attack += 1;
                w.critChance = clamp01(w.critChance + 0.05);
                w.dodgeChance = clamp01(w.dodgeChance - 0.02);
                break;
            case SKIRMISHER:
                w.dodgeChance = clamp01(w.dodgeChance + 0.05);
                w.missChance  = clamp01(w.missChance  - 0.01);
                w.armor = Math.max(0, w.armor - 1);
                break;
            case SUPPORT:
                w.blockChance = clamp01(w.blockChance + 0.03);
                w.stunOnCritChance = clamp01(w.stunOnCritChance + 0.05);
                w.potions += 1;
                break;
        }
    }
    static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }
}

// ===================== ОРУЖИЕ =====================
enum Weapon {
    NONE(0,0,0,0.0,0.0),
    PIKE(1,0,1,-0.02,0.00),
    ZWEIHANDER(2,0,2,0.00,0.05),
    SWORD_BUCKLER(0,0,1,-0.01,0.00) { @Override void extra(Warrior w){ w.blockChance = clamp01(w.blockChance + 0.05); }},
    AXE(1,1,1,0.00,0.00),
    PISTOL(0,1,1,0.05,0.07);

    final int dmgBonus;
    final int armorPen;
    final int weight;
    final double missDelta;
    final double critDelta;

    Weapon(int dmgBonus, int armorPen, int weight, double missDelta, double critDelta) {
        this.dmgBonus = dmgBonus;
        this.armorPen = armorPen;
        this.weight = weight;
        this.missDelta = missDelta;
        this.critDelta = critDelta;
    }
    void applyTo(Warrior w) {
        w.attack += dmgBonus;
        w.missChance = clamp01(w.missChance + missDelta);
        w.critChance = clamp01(w.critChance + critDelta);
        w.pierce += armorPen;
        extra(w);
    }
    void extra(Warrior w) {}
    static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }
}

// ===================== MAIN =====================
public class Main {

    // --- Баланс / режимы
    static final int    LOW_HP_THRESHOLD    = 10;
    static final double TEAM_HEAL_CHANCE    = 0.50;
    static final boolean SHOW_ROUND_SUMMARY = true;

    // --- Логгер
    static final int BRIEF = 0, NORMAL = 1, VERBOSE = 2;
    static int LOG_LEVEL = NORMAL;
    static boolean COLOR = true;
    static final String RESET = "\u001B[0m", RED = "\u001B[31m", GREEN = "\u001B[32m",
            YELLOW = "\u001B[33m", CYAN = "\u001B[36m";

    public static void log(int need, String msg) { if (LOG_LEVEL >= need) System.out.println(msg); }
    public static String c(String color, String s){ return COLOR ? color + s + RESET : s; }

    // --- Пути для слотов
    static final String SAVES_DIR = "saves";
    static final String INDEX_PATH = SAVES_DIR + "/index.json";

    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);

        configureLogging(in);

        System.out.println("\nВыберите режим:");
        System.out.println(" 1) Дуэль (1 на 1)");
        System.out.println(" 2) Командная битва");
        System.out.println(" 3) Загрузить из JSON (старый способ)");
        System.out.println(" 4) Загрузить из списка сохранений (СЛОТЫ)");
        System.out.println(" 5) Кампания (WIP)");
        int mode = readInt(in, "Ваш выбор (1-5): ", 1, 5);

        if (mode == 5) {
            runCampaign(in);
            in.close();
            return;
        } else if (mode == 4) {
            var metas = listSavesPrint();
            if (!metas.isEmpty()) {
                int num = readInt(in, "Введите номер слота для загрузки: ", 1, metas.size());
                SaveGame sg = loadSaveByNumber(num);
                if (sg != null) {
                    LOG_LEVEL = sg.logLevel;
                    COLOR = sg.color;
                    runTeamBattleLoaded(in, sg);
                }
            }
            in.close();
            return;
        } else if (mode == 3) {
            System.out.print("Путь к сохранению (по умолчанию save.json): ");
            String p = in.nextLine().trim();
            if (p.isEmpty()) p = "save.json";
            SaveGame sg = loadGameJson(p);
            if (sg != null) {
                LOG_LEVEL = sg.logLevel;
                COLOR = sg.color;
                runTeamBattleLoaded(in, sg);
            }
            in.close();
            return;
        } else if (mode == 2) {
            runTeamBattle(in);
            in.close();
            return;
        }

        // Дуэль
        System.out.println("Выберите первого бойца: 1) Landsknecht  2) Swiss  3) Случайный  4) Список");
        int ch1 = readInt(in, "Ваш выбор (1-4): ", 1, 4);
        Warrior p1 = createWarrior(ch1, in); p1.teamTag = "[A]";
        System.out.println("Выберите второго бойца: 1) Landsknecht  2) Swiss  3) Случайный  4) Список");
        int ch2 = readInt(in, "Ваш выбор (1-4): ", 1, 4);
        Warrior p2 = createWarrior(ch2, in); p2.teamTag = "[B]";

        System.out.println("\nНачало кошачей свалки: " + p1.label() + " vs " + p2.label());

        while (p1.hp > 0 && p2.hp > 0) {
            boolean p1First = Math.random() < 0.5;
            Warrior first = p1First ? p1 : p2;
            Warrior second = p1First ? p2 : p1;

            log(BRIEF, c(CYAN, "\n→ В этом раунде первым ходит " + first.label()));

            if (first.tryStartTurn()) {
                if (first.hp <= LOW_HP_THRESHOLD && first.potions > 0) first.usePotion();
                else first.attack(second);
            }
            if (second.hp <= 0) break;

            if (second.tryStartTurn()) {
                if (second.hp <= LOW_HP_THRESHOLD && second.potions > 0) second.usePotion();
                else second.attack(first);
            }
        }
        System.out.println("\nБой окончен!");
        in.close();
    }

    // ===================== КОМПАНИЯ: СКЕЛЕТ ЦИКЛА =====================
    static void runCampaign(Scanner in) {
        System.out.println("\n=== КАМПАНИЯ (WIP) ===");

        CampaignState cs = new CampaignState();

        // стартовый набор: 2 бойца
        System.out.println("Соберём стартовый отряд (2 бойца).");
        for (int i = 0; i < 2; i++) {
            System.out.println("Стартовый боец #" + (i + 1) + ": 1) Landsknecht  2) Swiss  3) Случайный  4) Список");
            int ch = readInt(in, "Ваш выбор (1-4): ", 1, 4);
            cs.roster[i] = createWarrior(ch, in);
            cs.roster[i].teamTag = "[A]"; // твой отряд — команда A
        }

        boolean running = true;
        while (running) {
            System.out.println("\n=== День " + cs.day + " | 💰 Gold: " + cs.gold + " | Отряд живых: " + cs.aliveCount() + " ===");
            System.out.println(" 1) Лагерь / Магазин");
            System.out.println(" 2) Поход");
            System.out.println(" 3) Следующий бой");
            System.out.println(" 0) Выйти в главное меню");
            int pick = readInt(in, "Ваш выбор: ", 0, 3);

            if (pick == 0) {
                System.out.println("Выход из кампании...");
                break;
            } else if (pick == 1) {
                campMenu(in, cs);
            } else if (pick == 2) {
                doExpedition(in, cs);
            } else if (pick == 3) {
                doNextBattle(in, cs);
            }
        }
        System.out.println("Кампания завершена.");
    }

    static void campMenu(Scanner in, CampaignState cs) {
        System.out.println("\n— ЛАГЕРЬ / МАГАЗИН —");
        System.out.println(" 1) Купить зелье (15 gold, +1 к любому живому бойцу)");
        System.out.println(" 2) Просмотр отряда");
        System.out.println(" 0) Назад");
        int pick = readInt(in, "Ваш выбор: ", 0, 2);

        if (pick == 1) {
            if (cs.gold < 15) { System.out.println("Недостаточно золота."); return; }
            int idx = selectAliveWarrior(in, cs.roster, "Кому дать зелье?");
            if (idx == -1) return;
            cs.roster[idx].potions += 1;
            cs.gold -= 15;
            System.out.println("Куплено зелье для " + cs.roster[idx].label() + ". Осталось золота: " + cs.gold);
        } else if (pick == 2) {
            printTeam("Ваш отряд", cs.roster);
        }
    }

    static void doExpedition(Scanner in, CampaignState cs) {
        System.out.println("\n— ПОХОД —");
        double roll = Math.random();
        if (roll < 0.5) {
            int found = 10 + (int)(Math.random() * 11); // 10..20
            cs.gold += found;
            System.out.println("Найдены припасы и контракты: +" + found + " gold. Теперь: " + cs.gold);
        } else {
            System.out.println("Дороги пустынны. Без происшествий.");
        }
        cs.day += 1;
    }

    static void doNextBattle(Scanner in, CampaignState cs) {
        System.out.println("\n— СЛЕДУЮЩИЙ БОЙ —");

        Warrior[] teamA = buildActiveTeam(cs.roster);
        if (teamA.length == 0) {
            System.out.println("Все бойцы выбиты. Нечем сражаться.");
            return;
        }

        Warrior[] teamB = new Warrior[teamA.length];
        for (int i = 0; i < teamB.length; i++) {
            teamB[i] = Warrior.randomWarrior();
            teamB[i].teamTag = "[B]";
        }

        printTeam("Команда A (ваш отряд)", teamA);
        printTeam("Команда B (противник)", teamB);

        int round = 1;
        while (teamAlive(teamA) && teamAlive(teamB)) {
            playTeamRoundRandom(round, teamA, teamB);
            if (SHOW_ROUND_SUMMARY) {
                printTeam("Сводка: Команда A", teamA);
                printTeam("Сводка: Команда B", teamB);
                log(BRIEF, teamMiniSummary(teamA, teamB));
            }
            round++;
        }

        boolean win = teamAlive(teamA);
        System.out.println(win ? "🏆 Победа!" : "☠️ Поражение...");

        if (win) {
            int reward = 20 + (int)(Math.random() * 16); // 20..35
            cs.gold += reward;
            System.out.println("Награда: +" + reward + " gold. Всего: " + cs.gold);
        } else {
            int loss = 10 + (int)(Math.random() * 11); // 10..20
            cs.gold = Math.max(0, cs.gold - loss);
            System.out.println("Потери: -" + loss + " gold. Осталось: " + cs.gold);
        }

        syncBackToRoster(cs.roster, teamA);
        cs.day += 1;
    }

    static int selectAliveWarrior(Scanner in, Warrior[] team, String prompt) {
        List<Integer> aliveIdx = new ArrayList<>();
        System.out.println(prompt);
        for (int i = 0; i < team.length; i++) {
            Warrior w = team[i];
            if (w != null && w.hp > 0) {
                aliveIdx.add(i);
                System.out.println(" " + (aliveIdx.size()) + ") " + w.label() + " (hp=" + w.hp + ")");
            }
        }
        if (aliveIdx.isEmpty()) { System.out.println("Живых бойцов нет."); return -1; }
        int pick = readInt(in, "Номер: ", 1, aliveIdx.size());
        return aliveIdx.get(pick - 1);
    }

    static Warrior[] buildActiveTeam(Warrior[] roster) {
        List<Warrior> list = new ArrayList<>();
        for (Warrior w : roster) {
            if (w != null && w.hp > 0) {
                Warrior copy = cloneWarriorForBattle(w);
                copy.teamTag = "[A]";
                list.add(copy);
            }
        }
        return list.toArray(new Warrior[0]);
    }

    static Warrior cloneWarriorForBattle(Warrior w) {
        Warrior c = new Warrior(w.name, w.hp, w.attack);
        c.maxHp = w.maxHp;
        c.potions = w.potions;
        c.stunned = false;
        c.fatigue = 0;
        c.armor = w.armor;
        c.pierce = w.pierce;
        c.minDamage = w.minDamage;
        c.missChance = w.missChance;
        c.blockChance = w.blockChance;
        c.dodgeChance = w.dodgeChance;
        c.critChance = w.critChance;
        c.stunOnCritChance = w.stunOnCritChance;
        c.role = w.role;
        c.weapon = w.weapon;
        return c;
    }

    static void syncBackToRoster(Warrior[] roster, Warrior[] battleTeamA) {
        for (int i = 0; i < roster.length; i++) {
            Warrior base = roster[i];
            if (base == null) continue;
            Warrior match = null;
            for (Warrior bw : battleTeamA) {
                if (bw != null && bw.name.equals(base.name)) { match = bw; break; }
            }
            if (match != null) {
                base.hp = Math.max(0, Math.min(base.maxHp, match.hp));
                base.potions = match.potions;
            }
        }
    }

    // ===================== КОМАНДНАЯ БИТВА (как было) =====================
    static void runTeamBattle(Scanner in) {
        System.out.println("\n[Командная битва] Старт.");

        int sizeA = readInt(in, "Размер команды A (1-5): ", 1, 5);
        int sizeB = readInt(in, "Размер команды B (1-5): ", 1, 5);

        Warrior[] teamA = new Warrior[sizeA];
        Warrior[] teamB = new Warrior[sizeB];

        for (int i = 0; i < sizeA; i++) {
            System.out.println("A[" + (i + 1) + "] — выберите бойца: 1) Landsknecht  2) Swiss  3) Случайный  4) Список");
            int choice = readInt(in, "Ваш выбор (1-4): ", 1, 4);
            teamA[i] = createWarrior(choice, in);
            teamA[i].teamTag = "[A]";
        }
        for (int i = 0; i < sizeB; i++) {
            System.out.println("B[" + (i + 1) + "] — выберите бойца: 1) Landsknecht  2) Swiss  3) Случайный  4) Список");
            int choice = readInt(in, "Ваш выбор (1-4): ", 1, 4);
            teamB[i] = createWarrior(choice, in);
            teamB[i].teamTag = "[B]";
        }

        printTeam("Команда A", teamA);
        printTeam("Команда B", teamB);

        System.out.print("Сохранить старт боя в СЛОТ? (y/n, default n): ");
        String ansSave = in.nextLine().trim().toLowerCase();
        if (ansSave.equals("y")) {
            System.out.print("Имя сохранения (например, \"Старт боя\"): ");
            String nm = in.nextLine().trim();
            saveGameToNewSlot(nm, teamA, teamB, 1);
        }

        int round = 1;
        while (teamAlive(teamA) && teamAlive(teamB)) {
            playTeamRoundRandom(round, teamA, teamB);

            if (SHOW_ROUND_SUMMARY) {
                printTeam("Сводка: Команда A", teamA);
                printTeam("Сводка: Команда B", teamB);
                log(BRIEF, teamMiniSummary(teamA, teamB));
            }

            System.out.print("[S] сохранить в СЛОТ, [Enter] продолжить: ");
            String hot = in.nextLine().trim().toLowerCase();
            if (hot.equals("s")) {
                System.out.print("Имя сохранения (Enter — по умолчанию): ");
                String nm = in.nextLine().trim();
                saveGameToNewSlot(nm, teamA, teamB, round + 1);
            }

            round++;
        }

        System.out.println();
        System.out.println(teamAlive(teamA) ? "🏆 Победила команда A!" : "🏆 Победила команда B!");
        System.out.println("[Командная битва] Завершена.");
    }

    static void runTeamBattleLoaded(Scanner in, SaveGame sg) {
        Warrior[] teamA = sg.teamA;
        Warrior[] teamB = sg.teamB;
        int round = Math.max(1, sg.round);

        for (Warrior w : teamA) if (w != null) w.teamTag = "[A]";
        for (Warrior w : teamB) if (w != null) w.teamTag = "[B]";

        printTeam("Команда A (загружено)", teamA);
        printTeam("Команда B (загружено)", teamB);

        while (teamAlive(teamA) && teamAlive(teamB)) {
            playTeamRoundRandom(round, teamA, teamB);

            if (SHOW_ROUND_SUMMARY) {
                printTeam("Сводка: Команда A", teamA);
                printTeam("Сводка: Команда B", teamB);
                log(BRIEF, teamMiniSummary(teamA, teamB));
            }

            System.out.print("[S] сохранить в СЛОТ, [Enter] продолжить: ");
            String hot = in.nextLine().trim().toLowerCase();
            if (hot.equals("s")) {
                System.out.print("Имя сохранения (Enter — по умолчанию): ");
                String nm = in.nextLine().trim();
                saveGameToNewSlot(nm, teamA, teamB, round + 1);
            }

            round++;
        }

        System.out.println();
        System.out.println(teamAlive(teamA) ? "🏆 Победила команда A!" : "🏆 Победила команда B!");
        System.out.println("[Командная битва] Завершена.");
    }

    static void playTeamRoundRandom(int roundNumber, Warrior[] teamA, Warrior[] teamB) {
        log(BRIEF, c(CYAN, "\n🎲 — Раунд " + roundNumber + " — (случайный порядок)"));
        List<Actor> order = buildRandomOrder(teamA, teamB);
        for (Actor act : order) {
            if (!teamAlive(teamA) || !teamAlive(teamB)) break;
            fighterSingleAttack(act.me, act.enemies);
        }
    }

    static List<Actor> buildRandomOrder(Warrior[] teamA, Warrior[] teamB) {
        List<Actor> order = new ArrayList<>();
        for (Warrior w : teamA) if (w.hp > 0) order.add(new Actor(w, teamB));
        for (Warrior w : teamB) if (w.hp > 0) order.add(new Actor(w, teamA));
        Collections.shuffle(order);
        return order;
    }

    static void fighterSingleAttack(Warrior attacker, Warrior[] enemyTeam) {
        if (attacker.hp <= 0) return;
        if (!attacker.tryStartTurn()) return;

        if (attacker.hp <= LOW_HP_THRESHOLD && attacker.potions > 0) {
            if (Math.random() < TEAM_HEAL_CHANCE) { attacker.usePotion(); return; }
        }

        Warrior target = randomAlive(enemyTeam);
        if (target != null) attacker.attack(target);
    }

    // ===================== JSON: БЫСТРЫЕ СЕЙВЫ (оставили) =====================
    static void saveGameJson(String path, Warrior[] teamA, Warrior[] teamB, int round) {
        try {
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
            SaveGame sg = new SaveGame(teamA, teamB, round, LOG_LEVEL, COLOR);
            java.nio.file.Files.writeString(java.nio.file.Path.of(path), gson.toJson(sg));
            System.out.println("✅ Сохранение выполнено: " + path);
        } catch (Exception e) {
            System.out.println("❌ Ошибка сохранения: " + e.getMessage());
        }
    }

    static SaveGame loadGameJson(String path) {
        try {
            String json = java.nio.file.Files.readString(java.nio.file.Path.of(path));
            com.google.gson.Gson gson = new com.google.gson.Gson();
            SaveGame sg = gson.fromJson(json, SaveGame.class);
            System.out.println("✅ Загрузка выполнена: " + path);
            return sg;
        } catch (Exception e) {
            System.out.println("❌ Ошибка загрузки: " + e.getMessage());
            return null;
        }
    }

    // ===================== СЛОТЫ: ДИРЕКТОРИЯ, ИНДЕКС, ФОРМАТ ВРЕМЕНИ =====================
    static void ensureSavesDir() {
        try { java.nio.file.Files.createDirectories(java.nio.file.Path.of(SAVES_DIR)); } catch (Exception ignored) {}
    }

    static java.util.List<SaveMeta> readSaveIndex() {
        ensureSavesDir();
        java.nio.file.Path p = java.nio.file.Path.of(INDEX_PATH);
        if (!java.nio.file.Files.exists(p)) return new java.util.ArrayList<>();
        try {
            String json = java.nio.file.Files.readString(p);
            com.google.gson.Gson gson = new com.google.gson.Gson();
            SaveMeta[] arr = gson.fromJson(json, SaveMeta[].class);
            java.util.List<SaveMeta> list = new java.util.ArrayList<>();
            if (arr != null) java.util.Collections.addAll(list, arr);
            list.sort((a,b) -> Long.compare(b.savedAt, a.savedAt));
            return list;
        } catch (Exception e) {
            System.out.println("⚠️ Не удалось прочитать index.json: " + e.getMessage());
            return new java.util.ArrayList<>();
        }
    }

    static void writeSaveIndex(java.util.List<SaveMeta> metas) {
        ensureSavesDir();
        try {
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
            String json = gson.toJson(metas);
            java.nio.file.Files.writeString(java.nio.file.Path.of(INDEX_PATH), json);
        } catch (Exception e) {
            System.out.println("⚠️ Не удалось записать index.json: " + e.getMessage());
        }
    }

    static String nextSaveId(java.util.List<SaveMeta> metas) {
        int max = 0;
        for (SaveMeta m : metas) {
            if (m.id != null && m.id.startsWith("save-")) {
                try {
                    int n = Integer.parseInt(m.id.substring(5));
                    if (n > max) max = n;
                } catch (NumberFormatException ignored) {}
            }
        }
        return String.format("save-%03d", max + 1);
    }

    static String fmtTime(long millis) {
        java.time.Instant inst = java.time.Instant.ofEpochMilli(millis);
        java.time.ZoneId tz = java.time.ZoneId.systemDefault();
        java.time.ZonedDateTime dt = java.time.ZonedDateTime.ofInstant(inst, tz);
        return dt.toLocalDate() + " " + dt.toLocalTime().withNano(0);
    }

    // ===================== СЛОТЫ: СОХРАНИТЬ/СПИСОК/ЗАГРУЗИТЬ =====================
    static void saveGameToNewSlot(String saveName, Warrior[] teamA, Warrior[] teamB, int round) {
        ensureSavesDir();
        java.util.List<SaveMeta> metas = readSaveIndex();
        String id = nextSaveId(metas);
        String path = SAVES_DIR + "/" + id + ".json";
        long now = System.currentTimeMillis();

        SaveGame sg = new SaveGame(teamA, teamB, round, LOG_LEVEL, COLOR);
        sg.saveName = (saveName == null || saveName.isBlank()) ? id : saveName.trim();
        sg.savedAtEpochMillis = now;

        try {
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
            java.nio.file.Files.writeString(java.nio.file.Path.of(path), gson.toJson(sg));
            metas.add(new SaveMeta(id, sg.saveName, now, path));
            metas.sort((a,b) -> Long.compare(b.savedAt, a.savedAt));
            writeSaveIndex(metas);
            System.out.println("✅ Сохранено в слот: " + id + " — \"" + sg.saveName + "\" (" + fmtTime(now) + ")");
        } catch (Exception e) {
            System.out.println("❌ Ошибка сохранения слота: " + e.getMessage());
        }
    }

    static java.util.List<SaveMeta> listSavesPrint() {
        java.util.List<SaveMeta> metas = readSaveIndex();
        if (metas.isEmpty()) {
            System.out.println("\nСохранения отсутствуют.");
            return metas;
        }
        System.out.println("\nСписок сохранений:");
        for (int i = 0; i < metas.size(); i++) {
            SaveMeta m = metas.get(i);
            System.out.println(" " + (i+1) + ") [" + m.id + "] " + m.saveName + " · " + fmtTime(m.savedAt) + " · " + m.path);
        }
        return metas;
    }

    static SaveGame loadSaveByNumber(int number) {
        java.util.List<SaveMeta> metas = readSaveIndex();
        if (number < 1 || number > metas.size()) {
            System.out.println("Неверный номер слота.");
            return null;
        }
        SaveMeta m = metas.get(number - 1);
        try {
            String json = java.nio.file.Files.readString(java.nio.file.Path.of(m.path));
            com.google.gson.Gson gson = new com.google.gson.Gson();
            SaveGame sg = gson.fromJson(json, SaveGame.class);
            System.out.println("✅ Загружено: [" + m.id + "] \"" + sg.saveName + "\" (" + fmtTime(sg.savedAtEpochMillis) + ")");
            return sg;
        } catch (Exception e) {
            System.out.println("❌ Ошибка загрузки из слота: " + e.getMessage());
            return null;
        }
    }

    // ===================== ВСПОМОГАТЕЛЬНОЕ =====================
    static void configureLogging(Scanner in) {
        System.out.println("\nНастройка лога:");
        System.out.println(" 0) Краткий (BRIEF)");
        System.out.println(" 1) Обычный (NORMAL)");
        System.out.println(" 2) Подробный (VERBOSE)");
        LOG_LEVEL = readInt(in, "Уровень лога (0-2): ", 0, 2);

        while (true) {
            System.out.print("Цветной вывод? (y/n): ");
            String ans = in.nextLine().trim().toLowerCase();
            if (ans.equals("y")) { COLOR = true;  break; }
            if (ans.equals("n")) { COLOR = false; break; }
            System.out.println("Введите 'y' или 'n'.");
        }
        System.out.println("Лог: " +
                (LOG_LEVEL==BRIEF?"BRIEF":LOG_LEVEL==NORMAL?"NORMAL":"VERBOSE") +
                ", цвет " + (COLOR?"вкл":"выкл"));
    }

    static boolean teamAlive(Warrior[] team) {
        for (Warrior w : team) if (w.hp > 0) return true;
        return false;
    }

    static Warrior randomAlive(Warrior[] team) {
        int alive = 0;
        for (Warrior w : team) if (w.hp > 0) alive++;
        if (alive == 0) return null;
        int k = (int)(Math.random() * alive);
        for (Warrior w : team) {
            if (w.hp > 0) {
                if (k == 0) return w;
                k--;
            }
        }
        return null;
    }

    static void printTeam(String title, Warrior[] team) {
        System.out.println("\n" + title + ":");
        for (int i = 0; i < team.length; i++) {
            Warrior w = team[i];
            String nm = String.format("%-14s", w.label());
            System.out.println((i + 1) + ") " + nm
                    + " (hp=" + w.hp + ", atk=" + w.attack
                    + ", arm=" + w.armor + ", pierce=" + w.pierce
                    + ", role=" + w.role + ", weap=" + w.weapon + ")");
        }
    }

    static String teamMiniSummary(Warrior[] teamA, Warrior[] teamB) {
        int aAlive = 0, bAlive = 0, aHp = 0, bHp = 0;
        for (Warrior w : teamA) { if (w.hp > 0) aAlive++; aHp += w.hp; }
        for (Warrior w : teamB) { if (w.hp > 0) bAlive++; bHp += w.hp; }
        return c(CYAN, "📊 Сводка: ") +
                "A живых " + aAlive + " (HP=" + aHp + ") | " +
                "B живых " + bAlive + " (HP=" + bHp + ")";
    }

    static int readInt(Scanner in, String prompt, int min, int max) {
        while (true) {
            System.out.print(prompt);
            String line = in.nextLine().trim();
            try {
                int v = Integer.parseInt(line);
                if (v < min || v > max) System.out.println("Введите число от " + min + " до " + max + ".");
                else return v;
            } catch (NumberFormatException e) {
                System.out.println("Нужно число. Повторите ввод.");
            }
        }
    }

    static Warrior createWarrior(int choice, Scanner in) {
        switch (choice) {
            case 1: {
                Warrior w = new Warrior("Landsknecht", 30, 5);
                w.armor = 2; w.role = Role.TANK; w.weapon = Weapon.ZWEIHANDER;
                w.role.applyTo(w); w.weapon.applyTo(w);
                return w;
            }
            case 2: {
                Warrior w = new Warrior("Swiss", 25, 6);
                w.armor = 1; w.role = Role.TANK;
                w.weapon = (Math.random() < 0.7) ? Weapon.PIKE : Weapon.SWORD_BUCKLER;
                w.role.applyTo(w); w.weapon.applyTo(w);
                return w;
            }
            case 3: return Warrior.randomWarrior();
            case 4: return pickFromGeneratedListLoop(in);
            default: return new Warrior("Landsknecht", 30, 5);
        }
    }

    static Warrior pickFromGeneratedListLoop(Scanner in) {
        int count = readInt(in, "Размер списка (2-20, по умолчанию 5): ", 2, 20);
        while (true) {
            Warrior[] list = generateWarriorList(count);
            System.out.println("\nСгенерированные бойцы:");
            for (int i = 0; i < list.length; i++) System.out.println((i + 1) + ") " + list[i].name);
            System.out.print("Выберите номер (1-" + list.length + "), или 'r' чтобы перегенерировать: ");
            String ans = in.nextLine().trim().toLowerCase();
            if (ans.equals("r")) continue;
            try {
                int idx = Integer.parseInt(ans);
                if (idx >= 1 && idx <= list.length) return list[idx - 1];
            } catch (NumberFormatException ignored) {}
            System.out.println("Неверный ввод. Попробуйте ещё раз.");
        }
    }

    static Warrior[] generateWarriorList(int count) {
        if (count < 2) count = 2;
        Warrior[] list = new Warrior[count];

        list[0] = new Warrior("Landsknecht", 30, 5);
        list[0].armor = 2; list[0].role = Role.TANK; list[0].weapon = Weapon.ZWEIHANDER;
        list[0].role.applyTo(list[0]); list[0].weapon.applyTo(list[0]);

        list[1] = new Warrior("Swiss", 25, 6);
        list[1].armor = 1; list[1].role = Role.TANK;
        list[1].weapon = (Math.random() < 0.7) ? Weapon.PIKE : Weapon.SWORD_BUCKLER;
        list[1].role.applyTo(list[1]); list[1].weapon.applyTo(list[1]);

        for (int i = 2; i < count; i++) list[i] = Warrior.randomWarrior();

        for (int i = 0; i < count; i++) {
            Warrior w = list[i];
            w.name = w.name + "(" + w.hp + "hp/" + w.attack + "atk)";
        }
        return list;
    }

    static class Actor {
        Warrior me; Warrior[] enemies;
        Actor(Warrior me, Warrior[] enemies) { this.me = me; this.enemies = enemies; }
    }
}

// ===================== WARRIOR =====================
class Warrior {
    String name;
    String teamTag = "";
    int hp, maxHp, attack;

    int potions = 1;
    boolean stunned = false;
    int fatigue = 0;

    int armor = 0;   // броня
    int pierce = 0;  // пробитие

    int minDamage = 1;
    double missChance  = 0.20;
    double blockChance = 0.15;
    double dodgeChance = 0.10;
    double critChance  = 0.10;
    double stunOnCritChance = 0.25;

    Role role = Role.NONE;
    Weapon weapon = Weapon.NONE;

    Warrior(String name, int hp, int attack) {
        this.name = name; this.hp = hp; this.maxHp = hp; this.attack = attack;
    }

    String label() { return (teamTag == null || teamTag.isEmpty() ? "" : teamTag + " ") + name; }

    static Warrior randomWarrior() {
        String[] names = { "Spaniard", "Gallowglass", "Conquistador", "Condottiere", "Reiter" };
        String chosenName = names[(int)(Math.random() * names.length)];
        int hp  = 22 + (int)(Math.random() * 14);
        int atk = 4  + (int)(Math.random() * 4);
        Warrior w = new Warrior(chosenName, hp, atk);

        w.potions          = (int)(Math.random() * 3);
        w.blockChance      = 0.10 + Math.random() * 0.10;
        w.dodgeChance      = 0.05 + Math.random() * 0.10;
        w.critChance       = 0.08 + Math.random() * 0.10;
        w.missChance       = 0.15 + Math.random() * 0.10;
        w.stunOnCritChance = 0.20 + Math.random() * 0.15;

        if ("Reiter".equals(chosenName)) w.armor = 1;

        switch (w.name) {
            case "Reiter":       w.role=Role.SKIRMISHER; w.weapon=Weapon.PISTOL; break;
            case "Gallowglass":  w.role=Role.TANK;       w.weapon=Weapon.AXE;    break;
            case "Conquistador": w.role=Role.DUELIST;    w.weapon=Weapon.SWORD_BUCKLER; break;
            case "Condottiere":  w.role=Role.SUPPORT;    w.weapon=Weapon.SWORD_BUCKLER; break;
            case "Spaniard":     w.role=Role.DUELIST;    w.weapon=Weapon.SWORD_BUCKLER; break;
            default:             w.role=Role.NONE;       w.weapon=Weapon.NONE;
        }
        w.role.applyTo(w); w.weapon.applyTo(w);
        return w;
    }

    boolean tryStartTurn() {
        if (stunned) {
            Main.log(Main.NORMAL, "⏸ " + label() + " оглушён и пропускает ход!");
            stunned = false;
            return false;
        }
        return hp > 0;
    }

    void usePotion() {
        if (potions <= 0) return;
        int heal = 8;
        int before = hp;
        hp = Math.min(maxHp, hp + heal);
        potions--;
        Main.log(Main.BRIEF, "🧪 " + label() + " выпил зелье (+" + Main.c(Main.GREEN, String.valueOf(hp - before))
                + " hp). Осталось зелий: " + potions + ". Текущее hp: " + hp);
    }

    void attack(Warrior enemy) {
        if (Math.random() < missChance) { Main.log(Main.VERBOSE, "🌀 " + label() + " промахнулся по " + enemy.label() + "!"); return; }
        if (Math.random() < enemy.blockChance) { Main.log(Main.VERBOSE, "🛡 " + enemy.label() + " заблокировал удар " + label() + "!"); return; }
        if (Math.random() < enemy.dodgeChance) { Main.log(Main.VERBOSE, "💨 " + enemy.label() + " увернулся от удара " + label() + "!"); return; }

        int damage = Math.max(minDamage, this.attack - fatigue);

        boolean crit = Math.random() < critChance;
        if (crit) {
            damage *= 2;
            Main.log(Main.BRIEF, "⚡ " + label() + " нанёс " + Main.c(Main.YELLOW, "КРИТИЧЕСКИЙ") + " удар!");
        }

        int effectiveArmor = Math.max(0, enemy.armor - this.pierce);
        int finalDamage = Math.max(1, damage - effectiveArmor);
        int absorbed = damage - finalDamage;

        enemy.hp -= finalDamage;
        if (enemy.hp <= 0) {
            enemy.hp = 0;
            Main.log(Main.BRIEF, "💀 " + enemy.label() + " умер! Убийца — " + label());
            fatigue++;
            Main.log(Main.NORMAL, "⚔️ " + label() + " ударил " + enemy.label() +
                    " на " + Main.c(Main.RED, String.valueOf(finalDamage)) + " урона" +
                    (absorbed > 0 ? " (🧱 броня поглотила " + absorbed + ")" : "") +
                    ", у него осталось " + Main.c(Main.RED, String.valueOf(enemy.hp)) + " hp" +
                    " (усталость " + fatigue + ")");
            return;
        }

        if (crit && Math.random() < stunOnCritChance) {
            enemy.stunned = true;
            Main.log(Main.NORMAL, "🔔 " + enemy.label() + " оглушён и пропустит следующий ход!");
        }

        fatigue++;
        Main.log(Main.NORMAL, "⚔️ " + label() + " ударил " + enemy.label() +
                " на " + Main.c(Main.RED, String.valueOf(finalDamage)) + " урона" +
                (absorbed > 0 ? " (🧱 броня поглотила " + absorbed + ")" : "") +
                ", у него осталось " + Main.c(Main.RED, String.valueOf(enemy.hp)) + " hp" +
                " (усталость " + fatigue + ")");
    }
}

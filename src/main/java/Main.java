import java.util.*;

// ===================== МОДЕЛЬ СОХРАНЕНИЯ =====================
class SaveGame {
    Warrior[] teamA;
    Warrior[] teamB;
    int round;
    int logLevel;
    boolean color;

    SaveGame() {}
    SaveGame(Warrior[] teamA, Warrior[] teamB, int round, int logLevel, boolean color) {
        this.teamA = teamA;
        this.teamB = teamB;
        this.round = round;
        this.logLevel = logLevel;
        this.color = color;
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

    // Баланс/режимы
    static final int    LOW_HP_THRESHOLD    = 10;
    static final double TEAM_HEAL_CHANCE    = 0.50;
    static final boolean SHOW_ROUND_SUMMARY = true;

    // Логгер
    static final int BRIEF = 0, NORMAL = 1, VERBOSE = 2;
    static int LOG_LEVEL = NORMAL;
    static boolean COLOR = true;
    static final String RESET = "\u001B[0m", RED = "\u001B[31m", GREEN = "\u001B[32m",
            YELLOW = "\u001B[33m", CYAN = "\u001B[36m";

    public static void log(int need, String msg) { if (LOG_LEVEL >= need) System.out.println(msg); }
    public static String c(String color, String s){ return COLOR ? color + s + RESET : s; }

    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);

        configureLogging(in);

        System.out.println("\nВыберите режим боя:");
        System.out.println(" 1) Дуэль (1 на 1)");
        System.out.println(" 2) Командная битва");
        System.out.println(" 3) Загрузить командную битву из JSON");
        int mode = readInt(in, "Ваш выбор (1-3): ", 1, 3);

        if (mode == 3) {
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

    // ===================== КОМАНДНАЯ БИТВА =====================
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

        // Предложить сохранить старт боя
        System.out.print("Сохранить старт боя в JSON? (y/n, default n): ");
        String ansSave = in.nextLine().trim().toLowerCase();
        if (ansSave.equals("y")) saveGameJson("save.json", teamA, teamB, 1);

        int round = 1;
        while (teamAlive(teamA) && teamAlive(teamB)) {
            playTeamRoundRandom(round, teamA, teamB);

            if (SHOW_ROUND_SUMMARY) {
                printTeam("Сводка: Команда A", teamA);
                printTeam("Сводка: Команда B", teamB);
                log(BRIEF, teamMiniSummary(teamA, teamB));
            }

            System.out.print("[S] сохранить и продолжить, [Enter] просто продолжить: ");
            String hot = in.nextLine().trim().toLowerCase();
            if (hot.equals("s")) saveGameJson("save.json", teamA, teamB, round + 1);

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

            System.out.print("[S] сохранить и продолжить, [Enter] просто продолжить: ");
            String hot = in.nextLine().trim().toLowerCase();
            if (hot.equals("s")) saveGameJson("save.json", teamA, teamB, round + 1);

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

    // ===================== SAVE / LOAD JSON =====================
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

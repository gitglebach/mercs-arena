import java.util.*;

// -------------------- РОЛИ --------------------
enum Role {
    NONE,           // без изменений
    TANK,           // бронированный
    DUELIST,        // урон/крит
    SKIRMISHER,     // мобильный/уклон
    SUPPORT;        // поддержка

    // Пассивно модифицируем статы бойца (один раз при создании)
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

// -------------------- ОРУЖИЕ --------------------
enum Weapon {
    NONE(0, 0, 0, 0.0, 0.0),          // dmg, armorPen, weight, missΔ, critΔ
    PIKE(1, 0, 1, -0.02, 0.00),       // пика/алебарда: +урон, точнее
    ZWEIHANDER(2, 0, 2,  0.00, 0.05), // двуручник: +урон, +крит
    SWORD_BUCKLER(0, 0, 1, -0.01, 0.00) { @Override void extra(Warrior w){
        w.blockChance = clamp01(w.blockChance + 0.05);
    }},
    AXE(1, 1, 1, 0.00, 0.00),         // топор: +урон, +1 пробитие
    PISTOL(0, 1, 1, 0.05, 0.07);      // пистоль: +крит, +пробитие, но хуже меткость

    final int dmgBonus;
    final int armorPen;          // ← пробитие брони
    final int weight;            // пока не используем
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
        w.pierce += armorPen;     // суммарное пробитие на бойце
        extra(w);
    }

    void extra(Warrior w) { /* по умолчанию ничего */ }

    static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }
}

public class Main {

    // --- ПАРАМЕТРЫ БАЛАНСА / РЕЖИМОВ ---
    static final int    LOW_HP_THRESHOLD     = 10;    // "мало hp" для решения о лечении
    static final double TEAM_HEAL_CHANCE     = 0.50;  // шанс лечиться в командной битве
    static final boolean SHOW_ROUND_SUMMARY  = true;  // сводка после раунда (командный бой)

    // --- ЛОГГЕР ---
    static final int BRIEF = 0, NORMAL = 1, VERBOSE = 2;
    static int LOG_LEVEL = NORMAL; // ← задаём в консоли в начале
    static boolean COLOR = true;   // ← цвет ANSI

    static final String RESET = "\u001B[0m";
    static final String RED   = "\u001B[31m";
    static final String GREEN = "\u001B[32m";
    static final String YELLOW= "\u001B[33m";
    static final String CYAN  = "\u001B[36m";

    public static void log(int minLevel, String msg) {
        if (LOG_LEVEL >= minLevel) System.out.println(msg);
    }
    public static String c(String color, String s) { return COLOR ? color + s + RESET : s; }

    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);

        // Настройка лога через консоль (один раз)
        configureLogging(in);

        // Верхнее меню
        System.out.println("\nВыберите режим боя:");
        System.out.println(" 1) Дуэль (1 на 1)");
        System.out.println(" 2) Командная битва (случайный порядок ходов, по 1 атаке)");
        int mode = readInt(in, "Ваш выбор (1-2): ", 1, 2);

        if (mode == 2) {
            runTeamBattle(in);   // командный бой
            in.close();
            return;
        }

        // --- Дуэль ---
        System.out.println("Выберите первого бойца: 1) Landsknecht  2) Swiss  3) Случайный  4) Список");
        int choice1 = readInt(in, "Ваш выбор (1-4): ", 1, 4);
        Warrior p1 = createWarrior(choice1, in);
        p1.teamTag = "[A]";

        System.out.println("Выберите второго бойца: 1) Landsknecht  2) Swiss  3) Случайный  4) Список");
        int choice2 = readInt(in, "Ваш выбор (1-4): ", 1, 4);
        Warrior p2 = createWarrior(choice2, in);
        p2.teamTag = "[B]";

        System.out.println("\nНачало кошачей свалки: " + p1.label() + " vs " + p2.label());

        while (p1.hp > 0 && p2.hp > 0) {
            boolean p1First = Math.random() < 0.5;
            Warrior first  = p1First ? p1 : p2;
            Warrior second = p1First ? p2 : p1;

            log(BRIEF, c(CYAN, "\n→ В этом раунде первым ходит " + first.label()));

            if (first.tryStartTurn()) {
                if (first.hp <= LOW_HP_THRESHOLD && first.potions > 0) {
                    first.usePotion();
                } else {
                    first.attack(second);
                }
            }
            if (second.hp <= 0) break;

            if (second.tryStartTurn()) {
                if (second.hp <= LOW_HP_THRESHOLD && second.potions > 0) {
                    second.usePotion();
                } else {
                    second.attack(first);
                }
            }
            if (first.hp <= 0) break;
        }

        System.out.println("\nБой окончен!");
        in.close();
    }

    // ---------- КОМАНДНАЯ БИТВА (случайный порядок, по 1 атаке на ход) ----------

    static void runTeamBattle(Scanner in) {
        System.out.println("\n[Командная битва] Старт.");

        // Сбор команд
        int sizeA = readInt(in, "Размер команды A (1-5): ", 1, 5);
        int sizeB = readInt(in, "Размер команды B (1-5): ", 1, 5);

        Warrior[] teamA = new Warrior[sizeA];
        Warrior[] teamB = new Warrior[sizeB];

        // Заполнение A
        for (int i = 0; i < sizeA; i++) {
            System.out.println("A[" + (i + 1) + "] — выберите бойца: 1) Landsknecht  2) Swiss  3) Случайный  4) Список");
            int choice = readInt(in, "Ваш выбор (1-4): ", 1, 4);
            teamA[i] = createWarrior(choice, in);
            teamA[i].teamTag = "[A]";
        }
        // Заполнение B
        for (int i = 0; i < sizeB; i++) {
            System.out.println("B[" + (i + 1) + "] — выберите бойца: 1) Landsknecht  2) Swiss  3) Случайный  4) Список");
            int choice = readInt(in, "Ваш выбор (1-4): ", 1, 4);
            teamB[i] = createWarrior(choice, in);
            teamB[i].teamTag = "[B]";
        }

        printTeam("Команда A", teamA);
        printTeam("Команда B", teamB);

        // Главный цикл раундов
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

        System.out.println();
        System.out.println(teamAlive(teamA) ? "🏆 Победила команда A!" : "🏆 Победила команда B!");
        System.out.println("[Командная битва] Завершена.");
    }

    // РАУНД: случайный порядок ходов среди ВСЕХ живых; каждый делает 1 попытку
    static void playTeamRoundRandom(int roundNumber, Warrior[] teamA, Warrior[] teamB) {
        log(BRIEF, c(CYAN, "\n🎲 — Раунд " + roundNumber + " — (случайный порядок)"));

        List<Actor> order = buildRandomOrder(teamA, teamB);
        for (Actor act : order) {
            if (!teamAlive(teamA) || !teamAlive(teamB)) break;
            fighterSingleAttack(act.me, act.enemies);
        }
    }

    // Построить и перемешать список ходящих на раунд
    static List<Actor> buildRandomOrder(Warrior[] teamA, Warrior[] teamB) {
        List<Actor> order = new ArrayList<>();
        for (Warrior w : teamA) if (w.hp > 0) order.add(new Actor(w, teamB));
        for (Warrior w : teamB) if (w.hp > 0) order.add(new Actor(w, teamA));
        Collections.shuffle(order);
        return order;
    }

    // ОДНА попытка: лечимся по шансу ИЛИ бьём одну случайную живую цель
    static void fighterSingleAttack(Warrior attacker, Warrior[] enemyTeam) {
        if (attacker.hp <= 0) return;
        if (!attacker.tryStartTurn()) return; // оглушён — пропуск

        // шанс лечиться
        if (attacker.hp <= LOW_HP_THRESHOLD && attacker.potions > 0) {
            if (Math.random() < TEAM_HEAL_CHANCE) {
                attacker.usePotion();
                return; // лечился — ход закончил
            }
        }

        Warrior target = randomAlive(enemyTeam);
        if (target != null) attacker.attack(target);
    }

    // ---------- УТИЛИТЫ ----------

    static void configureLogging(Scanner in) {
        System.out.println("\nНастройка лога:");
        System.out.println(" 0) Краткий (BRIEF) — только важные события");
        System.out.println(" 1) Обычный (NORMAL)");
        System.out.println(" 2) Подробный (VERBOSE) — всё подряд");
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

        int k = (int)(Math.random() * alive); // индекс среди живых
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
            String nm = String.format("%-14s", w.label()); // выравнивание по колонке
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
                if (v < min || v > max) {
                    System.out.println("Введите число от " + min + " до " + max + ".");
                } else {
                    return v;
                }
            } catch (NumberFormatException e) {
                System.out.println("Нужно число. Повторите ввод.");
            }
        }
    }

    // выбор бойца: пресеты, случайный или список с регенерацией
    static Warrior createWarrior(int choice, Scanner in) {
        switch (choice) {
            case 1: { // Landsknecht — TANK, Zweihander
                Warrior w = new Warrior("Landsknecht", 30, 5);
                w.armor = 2;
                w.role = Role.TANK;
                w.weapon = Weapon.ZWEIHANDER;
                w.role.applyTo(w);
                w.weapon.applyTo(w);
                return w;
            }
            case 2: { // Swiss — TANK, обычно Pike; иногда Sword+Buckler
                Warrior w = new Warrior("Swiss", 25, 6);
                w.armor = 1;
                w.role = Role.TANK;
                w.weapon = (Math.random() < 0.7) ? Weapon.PIKE : Weapon.SWORD_BUCKLER;
                w.role.applyTo(w);
                w.weapon.applyTo(w);
                return w;
            }
            case 3: return Warrior.randomWarrior();         // из пула наёмников
            case 4: return pickFromGeneratedListLoop(in);   // список + перегенерация
            default: return new Warrior("Landsknecht", 30, 5);
        }
    }

    // список: спросить размер → сгенерировать → выбрать номер или перегенерировать
    static Warrior pickFromGeneratedListLoop(Scanner in) {
        int count = readInt(in, "Размер списка (2-20, по умолчанию 5): ", 2, 20);

        while (true) {
            Warrior[] list = generateWarriorList(count); // включает Landsknecht и Swiss
            System.out.println("\nСгенерированные бойцы:");
            for (int i = 0; i < list.length; i++) {
                System.out.println((i + 1) + ") " + list[i].name);
            }
            System.out.print("Выберите номер (1-" + list.length + "), или 'r' чтобы перегенерировать: ");
            String ans = in.nextLine().trim().toLowerCase();

            if (ans.equals("r")) continue; // перегенерируем заново
            try {
                int idx = Integer.parseInt(ans);
                if (idx >= 1 && idx <= list.length) {
                    return list[idx - 1];
                }
            } catch (NumberFormatException ignored) {}
            System.out.println("Неверный ввод. Попробуйте ещё раз.");
        }
    }

    // Генерация списка: первые двое фиксированы (Landsknecht и Swiss), остальные — случайные
    static Warrior[] generateWarriorList(int count) {
        if (count < 2) count = 2;
        Warrior[] list = new Warrior[count];

        // Landsknecht — TANK + Zweihander
        list[0] = new Warrior("Landsknecht", 30, 5);
        list[0].armor = 2;
        list[0].role = Role.TANK;
        list[0].weapon = Weapon.ZWEIHANDER;
        list[0].role.applyTo(list[0]);
        list[0].weapon.applyTo(list[0]);

        // Swiss — TANK + Pike(70%) / Sword+Buckler(30%)
        list[1] = new Warrior("Swiss", 25, 6);
        list[1].armor = 1;
        list[1].role = Role.TANK;
        list[1].weapon = (Math.random() < 0.7) ? Weapon.PIKE : Weapon.SWORD_BUCKLER;
        list[1].role.applyTo(list[1]);
        list[1].weapon.applyTo(list[1]);

        // Остальные — случайные историчные
        for (int i = 2; i < count; i++) list[i] = Warrior.randomWarrior();

        // Показать ключевые параметры в имени
        for (int i = 0; i < count; i++) {
            Warrior w = list[i];
            w.name = w.name + "(" + w.hp + "hp/" + w.attack + "atk)";
        }
        return list;
    }

    // «носитель» для случайного порядка: кто ходит и против какой команды
    static class Actor {
        Warrior me;
        Warrior[] enemies;
        Actor(Warrior me, Warrior[] enemies) { this.me = me; this.enemies = enemies; }
    }
}

// -------------------- WARRIOR --------------------

class Warrior {
    String name;
    String teamTag = "";         // тег команды для лога: "[A]" / "[B]"
    int hp;
    int maxHp;
    int attack;

    int potions = 1;
    boolean stunned = false;
    int fatigue = 0;

    int armor = 0;                 // ПЛОСКАЯ БРОНЯ
    int pierce = 0;                // ПРОБИТИЕ БРОНИ (из оружия/ролей)

    // Вероятности/порог
    int minDamage = 1;
    double missChance  = 0.20;
    double blockChance = 0.15;
    double dodgeChance = 0.10;
    double critChance  = 0.10;
    double stunOnCritChance = 0.25;

    // Новое
    Role role = Role.NONE;
    Weapon weapon = Weapon.NONE;

    Warrior(String name, int hp, int attack) {
        this.name = name;
        this.hp = hp;
        this.maxHp = hp;
        this.attack = attack;
    }

    String label() { // имя с тегом команды
        return (teamTag == null || teamTag.isEmpty() ? "" : teamTag + " ") + name;
    }

    static Warrior randomWarrior() {
        String[] names = { "Spaniard", "Gallowglass", "Conquistador", "Condottiere", "Reiter" };
        String chosenName = names[(int)(Math.random() * names.length)];

        int hp  = 22 + (int)(Math.random() * 14); // 22..35
        int atk = 4  + (int)(Math.random() * 4);  // 4..7
        Warrior w = new Warrior(chosenName, hp, atk);

        // базовые шансы/ресурсы
        w.potions            = (int)(Math.random() * 3);        // 0..2
        w.blockChance        = 0.10 + Math.random() * 0.10;     // 10%..20%
        w.dodgeChance        = 0.05 + Math.random() * 0.10;     // 5%..15%
        w.critChance         = 0.08 + Math.random() * 0.10;     // 8%..18%
        w.missChance         = 0.15 + Math.random() * 0.10;     // 15%..25%
        w.stunOnCritChance   = 0.20 + Math.random() * 0.15;     // 20%..35%

        // лёгкая броня рейтара по умолчанию
        if ("Reiter".equals(chosenName)) {
            w.armor = 1;
        }

        // Историчные дефолты роль/оружие
        switch (w.name) {
            case "Reiter":
                w.role = Role.SKIRMISHER;
                w.weapon = Weapon.PISTOL;
                break;
            case "Gallowglass":
                w.role = Role.TANK;
                w.weapon = Weapon.AXE;
                break;
            case "Conquistador":
                w.role = Role.DUELIST;
                w.weapon = Weapon.SWORD_BUCKLER;
                break;
            case "Condottiere":
                w.role = Role.SUPPORT;
                w.weapon = Weapon.SWORD_BUCKLER;
                break;
            case "Spaniard":
                w.role = Role.DUELIST;
                w.weapon = Weapon.SWORD_BUCKLER;
                break;
            default:
                w.role = Role.NONE;
                w.weapon = Weapon.NONE;
        }
        // Применить модификаторы
        w.role.applyTo(w);
        w.weapon.applyTo(w);

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
        // 1) ПРОМАХ
        if (Math.random() < missChance) {
            Main.log(Main.VERBOSE, "🌀 " + label() + " промахнулся по " + enemy.label() + "!");
            return;
        }
        // 2) БЛОК
        if (Math.random() < enemy.blockChance) {
            Main.log(Main.VERBOSE, "🛡 " + enemy.label() + " заблокировал удар " + label() + "!");
            return;
        }
        // 3) УКЛОНЕНИЕ
        if (Math.random() < enemy.dodgeChance) {
            Main.log(Main.VERBOSE, "💨 " + enemy.label() + " увернулся от удара " + label() + "!");
            return;
        }

        // 4) Базовый урон с учётом усталости (не ниже minDamage)
        int damage = Math.max(minDamage, this.attack - fatigue);

        // 5) Крит (x2)
        boolean crit = Math.random() < critChance;
        if (crit) {
            damage *= 2;
            Main.log(Main.BRIEF, "⚡ " + label() + " нанёс " + Main.c(Main.YELLOW, "КРИТИЧЕСКИЙ") + " удар!");
        }

        // 6) БРОНЯ с учётом ПРОБИТИЯ
        int effectiveArmor = Math.max(0, enemy.armor - this.pierce); // ← пробитие
        int finalDamage = Math.max(1, damage - effectiveArmor);
        int absorbed = damage - finalDamage;

        enemy.hp -= finalDamage;
        if (enemy.hp <= 0) {
            enemy.hp = 0;
            Main.log(Main.BRIEF, "💀 " + enemy.label() + " умер! Убийца — " + label());
            fatigue++;
            Main.log(Main.NORMAL,
                    "⚔️ " + label() + " ударил " + enemy.label() +
                            " на " + Main.c(Main.RED, String.valueOf(finalDamage)) + " урона" +
                            (absorbed > 0 ? " (🧱 броня поглотила " + absorbed + ")" : "") +
                            ", у него осталось " + Main.c(Main.RED, String.valueOf(enemy.hp)) + " hp" +
                            " (усталость " + fatigue + ")"
            );
            return;
        }

        // 7) Оглушение ТОЛЬКО при крите (если цель ещё жива)
        if (crit && Math.random() < stunOnCritChance) {
            enemy.stunned = true;
            Main.log(Main.NORMAL, "🔔 " + enemy.label() + " оглушён и пропустит следующий ход!");
        }

        // 8) Рост усталости
        fatigue++;

        // 9) Финальный лог удара (не смертельный)
        Main.log(Main.NORMAL,
                "⚔️ " + label() + " ударил " + enemy.label() +
                        " на " + Main.c(Main.RED, String.valueOf(finalDamage)) + " урона" +
                        (absorbed > 0 ? " (🧱 броня поглотила " + absorbed + ")" : "") +
                        ", у него осталось " + Main.c(Main.RED, String.valueOf(enemy.hp)) + " hp" +
                        " (усталость " + fatigue + ")"
        );
    }
}

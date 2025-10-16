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

    // v3: полная кампания (если слот про кампанию)
    CampaignState campaign;

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

// ===================== ВАЛЮТА =====================
enum Currency { GULDEN, THALER, DUCAT }

// ===================== КАМПАНИЯ: СОСТОЯНИЕ =====================
class CampaignState {
    int day = 1;
    int difficulty = 1; // 1-легко, 2-норма, 3-сложно

    // Валюты (новые)
    int gulden = 100;
    int thaler = 0;
    int ducat  = 0;

    // Запасы лута (пул лагеря)
    int stashPotions = 0;
    int stashArmorPatches = 0; // временная броня (+1 к броне на следующий бой)
    int stashLightArmor = 0;   // постоянная +1 броня
    int stashBasicWeapons = 0; // один раз выдать базовое оружие

    // максимум 5 бойцов в активном отряде
    Warrior[] roster = new Warrior[5];
    // резерв: до 5
    Warrior[] reserve = new Warrior[5];

    // Пул найма на текущий день
    List<Main.RecruitCandidate> recruitPool = null;
    int recruitPoolDay = -1;

    // Фокус-метка от ротмистра на следующий бой
    boolean focusTarget = false;

    int aliveCount() {
        int c = 0;
        for (Warrior w : roster) if (w != null && w.hp > 0) c++;
        return c;
    }
}

// ===================== РОЛИ =====================
enum Role {
    NONE, TANK, DUELIST, SKIRMISHER, SUPPORT, COMMANDER, ROTMEISTER;

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
            case COMMANDER:
                w.blockChance = clamp01(w.blockChance + 0.05);
                w.attack += 2;
                w.potions += 2;
                break;
            case ROTMEISTER:
                w.attack += 2;
                w.blockChance = clamp01(w.blockChance + 0.08);
                w.critChance = clamp01(w.critChance + 0.10);
                w.potions += 3;
                w.isRotmeister = true;
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

// ===================== СТОЙКИ/ДЕЙСТВИЯ/ПРИКАЗЫ =====================
enum StanceType { NONE, AGGRESSIVE, DEFENSIVE }
enum PlayerAction { ATTACK, POTION_SELF, POTION_ALLY, STANCE_AGGR, STANCE_DEF }
class RoundOrder { Integer focusEnemyIndexB = null; }

// ===================== MAIN =====================
public class Main {

    // Баланс / режимы
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

    // СЛОТЫ
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
            List<SaveMeta> metas = listSavesPrint();
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

    // ===================== КАМПАНИЯ (ЦИКЛ) =====================
    static void runCampaign(Scanner in) {
        System.out.println("\n=== КАМПАНИЯ (WIP) ===");
        CampaignState cs = new CampaignState();

        // стартовый набор: 2 бойца
        System.out.println("Соберём стартовый отряд (2 бойца).");
        for (int i = 0; i < 2; i++) {
            System.out.println("Стартовый боец #" + (i + 1) + ": 1) Landsknecht  2) Swiss  3) Случайный  4) Список");
            int ch = readInt(in, "Ваш выбор (1-4): ", 1, 4);
            cs.roster[i] = createWarrior(ch, in);
            cs.roster[i].teamTag = "[A]";
        }
        assignRotmeister(cs, in);

        boolean running = true;
        while (running) {
            System.out.println("\n=== День " + cs.day +
                    " | 💰 Gulden: " + cs.gulden + " | Thaler: " + cs.thaler + " | Ducat: " + cs.ducat +
                    " | Отряд живых: " + cs.aliveCount() + " ===");
            System.out.println(" 1) Лагерь / Магазин / Снаряжение");
            System.out.println(" 2) Поход");
            System.out.println(" 3) Следующий бой");
            System.out.println(" 4) Сохранить кампанию (в слот)");
            System.out.println(" 5) Загрузить кампанию (из списка слотов)");
            System.out.println(" 0) Выйти в главное меню");
            int pick = readInt(in, "Ваш выбор: ", 0, 5);

            if (pick == 0) {
                System.out.println("Выход из кампании...");
                break;
            } else if (pick == 1) {
                campMenu(in, cs);
            } else if (pick == 2) {
                doExpedition(in, cs);
            } else if (pick == 3) {
                doNextBattle(in, cs);
            } else if (pick == 4) {
                System.out.print("Имя сохранения кампании (Enter — по умолчанию): ");
                String nm = in.nextLine().trim();
                saveCampaignToNewSlot(nm, cs);
            } else if (pick == 5) {
                List<SaveMeta> metas = listSavesPrint();
                if (!metas.isEmpty()) {
                    int num = readInt(in, "Номер слота: ", 1, metas.size());
                    CampaignState loaded = loadCampaignByNumber(num);
                    if (loaded != null) cs = loaded;
                }
            }

            Warrior rotmeister = null;
            for (Warrior w : cs.roster) if (w != null && w.isRotmeister) { rotmeister = w; break; }
            if (rotmeister != null) rotmeisterMenu(in, cs);
        }
        System.out.println("Кампания завершена.");
    }

    // Назначение ротмистра
    static void assignRotmeister(CampaignState cs, Scanner in) {
        for (Warrior w : cs.roster) if (w != null && w.isRotmeister) { System.out.println("Ротмейстер уже выбран: " + w.label()); return; }
        System.out.println("Выберите Ротмейстера из отряда:");
        printTeam("Ваш отряд", cs.roster, true);
        int idx = readInt(in, "Выберите Ротмейстера (номер отряда): ", 1, cs.roster.length);
        Warrior r = cs.roster[idx - 1];
        if (r != null) r.setRotmeister();
    }

    // Меню ротмистра (командование между днями)
    static void rotmeisterMenu(Scanner in, CampaignState cs) {
        Warrior rot = null;
        for (Warrior w : cs.roster) if (w != null && w.isRotmeister) { rot = w; break; }
        if (rot == null) return;

        System.out.println("\n🌟 Ротмейстер " + rot.label() + " (Уровень " + rot.level + "):");
        System.out.println(" 0) Пропустить");
        int opt = 1;
        if (rot.level >= 1) { System.out.println(" " + (opt++) + ") Дать зелье союзнику (из личных)"); }
        if (rot.level >= 2) { System.out.println(" " + (opt++) + ") Установить атакующую стойку (на след. ход)"); }
        if (rot.level >= 3) { System.out.println(" " + (opt++) + ") Установить защитную стойку (до след. хода)"); }
        if (rot.level >= 4) { System.out.println(" " + (opt++) + ") Фокус атаки в следующем бою"); }
        if (rot.level >= 5) { System.out.println(" " + (opt++) + ") Массовое лечение (тратит 2 личных зелья)"); }
        if (rot.level >= 6) { System.out.println(" " + (opt++) + ") Боевой клич (бонусы на следующий бой)"); }

        int maxChoice = opt - 1;
        int choice = readInt(in, "Ваш выбор (0-" + maxChoice + "): ", 0, maxChoice);

        int cursor = 1;
        if (choice == 0) { System.out.println("Ротмейстер пропускает."); return; }
        if (rot.level >= 1 && choice == cursor++) {
            int i = selectAliveWarrior(in, cs.roster, "Выберите союзника для зелья:");
            if (i != -1) {
                if (rot.potions > 0) rot.usePotionOn(cs.roster[i]);
                else System.out.println("У Ротмейстера нет зелий.");
            }
            return;
        }
        if (rot.level >= 2 && choice == cursor++) { rot.nextTurnStance = StanceType.AGGRESSIVE; System.out.println("⚔ Стойка атакующая на следующий ход."); return; }
        if (rot.level >= 3 && choice == cursor++) { rot.defenseStance = StanceType.DEFENSIVE; System.out.println("🛡 Стойка защитная до следующего хода."); return; }
        if (rot.level >= 4 && choice == cursor++) { cs.focusTarget = true; System.out.println("🎯 Фокус атаки будет применён в следующем бою."); return; }
        if (rot.level >= 5 && choice == cursor++) { massHeal(cs, rot); return; }
        if (rot.level >= 6 && choice == cursor)   { battleCry(cs, rot); return; }
    }

    static void massHeal(CampaignState cs, Warrior rot) {
        if (rot.potions < 2) { System.out.println("Не хватает зелий (нужно 2)."); return; }
        System.out.println("🌟 Массовое лечение!");
        int healed = 0;
        for (Warrior w : cs.roster) {
            if (w != null && w.hp > 0 && w.hp < w.maxHp) {
                int before = w.hp;
                w.hp = Math.min(w.maxHp, w.hp + 6);
                if (w.hp > before) { healed++; System.out.println("  " + w.label() + " +" + (w.hp - before) + " hp"); }
            }
        }
        rot.potions -= 2;
        System.out.println("Исцелено: " + healed + ". Зелий у ротмистра осталось: " + rot.potions);
    }

    static void battleCry(CampaignState cs, Warrior rot) {
        System.out.println("🗣️ Боевой клич! Отряд вдохновлён.");
        for (Warrior w : cs.roster) if (w != null && w.hp > 0) w.battleCryBonus = true;
    }

    // ===================== ЛАГЕРЬ / РЫНОК / СНАРЯЖЕНИЕ =====================
    static void campMenu(Scanner in, CampaignState cs) {
        while (true) {
            System.out.println("\n— ЛАГЕРЬ —");
            System.out.println(" 1) Рынок");
            System.out.println(" 2) Снаряжение");
            System.out.println(" 3) Найм бойцов");
            System.out.println(" 4) Резерв (просмотр/обмен/уволить)");
            System.out.println(" 5) Повышения (распределить уровни)");
            System.out.println(" 6) Просмотр отряда");
            System.out.println(" 0) Назад");
            int pick = readInt(in, "Ваш выбор: ", 0, 6);
            if (pick == 0) return;
            if (pick == 1) marketMenu(in, cs);
            else if (pick == 2) equipmentMenu(in, cs);
            else if (pick == 3) hireMenu(in, cs);
            else if (pick == 4) reserveMenu(in, cs);
            else if (pick == 5) handlePendingLevelUps(in, cs.roster);
            else if (pick == 6) {
                printTeam("Ваш отряд", cs.roster, true);
                System.out.println("Запасы: Potions=" + cs.stashPotions + ", Patches=" + cs.stashArmorPatches +
                        ", LightArmor=" + cs.stashLightArmor + ", BasicWeapons=" + cs.stashBasicWeapons);
                System.out.println("Кошель: Gulden=" + cs.gulden + ", Thaler=" + cs.thaler + ", Ducat=" + cs.ducat);
            }
        }
    }

    // === Найт бойцов ===
    static class RecruitCandidate {
        Warrior warrior; int costG; int costT; int costD;
        RecruitCandidate(Warrior w, int g, int t, int d) { this.warrior=w; this.costG=g; this.costT=t; this.costD=d; }
        String label() {
            return warrior.name + " (hp=" + warrior.hp + ", atk=" + warrior.attack + ", arm=" + warrior.armor + 
                    ", role=" + warrior.role + ", weap=" + warrior.weapon + ") — цена: " + costG + "G/" + costT + "T/" + costD + "D";
        }
    }

    static List<RecruitCandidate> generateRecruitPool(int count, Warrior[] roster, Warrior[] reserve, int rotmeisterLevel) {
        if (count < 5) count = 5; if (count > 5) count = 5;
        Set<String> usedNames = new HashSet<>();
        if (roster != null) for (Warrior w : roster) if (w != null) usedNames.add(w.name);
        if (reserve != null) for (Warrior w : reserve) if (w != null) usedNames.add(w.name);

        List<RecruitCandidate> pool = new ArrayList<>();
        while (pool.size() < count) {
            Warrior w = Warrior.randomWarriorWithNameExclusions(usedNames);
            // Кэп по уровню
            while (w.level > rotmeisterLevel) { w.level--; }
            // Стоимость от силы бойца + уровень
            int score = w.maxHp + w.attack * 4 + w.armor * 3 + w.pierce * 3 + (w.level - 1) * 6;
            int g = 12 + Math.max(0, score / 5);
            int t = (w.armor >= 2 ? 1 : 0);
            int d = (w.weapon != Weapon.NONE ? 1 : 0);
            if (!usedNames.contains(w.name)) {
                usedNames.add(w.name);
                pool.add(new RecruitCandidate(w, g, t, d));
            }
        }
        return pool;
    }

    static int findEmptyRosterIndex(Warrior[] roster) {
        for (int i = 0; i < roster.length; i++) if (roster[i] == null || roster[i].hp <= 0 && roster[i].name == null) return i;
        for (int i = 0; i < roster.length; i++) if (roster[i] == null) return i;
        return -1;
    }

    static void handlePendingLevelUps(Scanner in, Warrior[] roster) {
        boolean any = false;
        for (Warrior w : roster) if (w != null && w.pendingLevelUps > 0) { any = true; break; }
        if (!any) { System.out.println("Нет ожидающих повышений."); return; }
        for (Warrior w : roster) {
            if (w == null || w.pendingLevelUps <= 0) continue;
            System.out.println("\nПовышение для " + w.label() + " (уровень " + w.level + ") — осталось выборов: " + w.pendingLevelUps);
            System.out.println(" 1) +5 HP\n 2) +2 ATK\n 3) +1 ARMOR\n 4) +1 PIERCE\n 5) Перка: +5% BLOCK\n 6) Перка: +5% CRIT\n 7) Перка: +5% DODGE");
            int pick = readInt(in, "Ваш выбор (1-7): ", 1, 7);
            switch (pick) {
                case 1: w.maxHp += 5; w.hp = Math.min(w.maxHp, w.hp + 5); break;
                case 2: w.attack += 2; break;
                case 3: w.armor += 1; break;
                case 4: w.pierce += 1; break;
                case 5: w.blockChance = Role.clamp01(w.blockChance + 0.05); break;
                case 6: w.critChance  = Role.clamp01(w.critChance  + 0.05); break;
                case 7: w.dodgeChance = Role.clamp01(w.dodgeChance + 0.05); break;
            }
            w.pendingLevelUps--;
            System.out.println("Выбор применён. Осталось: " + w.pendingLevelUps);
            if (w.pendingLevelUps > 0) { System.out.println("Ещё одно повышение этому бойцу."); }
        }
    }

    static void hireMenu(Scanner in, CampaignState cs) {
        while (true) {
            Warrior rot = null; for (Warrior w : cs.roster) if (w != null && w.isRotmeister) { rot = w; break; }
            int allowed = (rot == null) ? 0 : (rot.level / 3);
            int aliveHires = countAliveRecruits(cs.roster) + countAliveRecruits(cs.reserve);
            int hiresLeft = Math.max(0, allowed - aliveHires);

            if (cs.recruitPool == null || cs.recruitPoolDay != cs.day) {
                cs.recruitPool = generateRecruitPool(5, cs.roster, cs.reserve, rot!=null?rot.level:1);
                cs.recruitPoolDay = cs.day;
            }
            List<RecruitCandidate> pool = cs.recruitPool;
            System.out.println("\n— НАЙМ —");
            System.out.println("Кошель: Gulden=" + cs.gulden + ", Thaler=" + cs.thaler + ", Ducat=" + cs.ducat);
            System.out.println("Доступно наймов по уровню Ротмейстера: " + hiresLeft);
            for (int i = 0; i < pool.size(); i++) System.out.println(" " + (i+1) + ") " + pool.get(i).label());
            System.out.println(" 0) Назад");
            System.out.print("Ваш выбор: ");
            String ans = in.nextLine().trim().toLowerCase();
            if (ans.equals("0")) return;
            int idx;
            try { idx = Integer.parseInt(ans); } catch (NumberFormatException e) { System.out.println("Неверный ввод."); continue; }
            if (idx < 1 || idx > pool.size()) { System.out.println("Нет такого кандидата."); continue; }
            if (hiresLeft <= 0) { System.out.println("Лимит наймов исчерпан. Повышайте уровень Ротмейстера или увольняйте бойцов."); continue; }
            RecruitCandidate rc = pool.get(idx - 1);
            if (cs.gulden < rc.costG || cs.thaler < rc.costT || cs.ducat < rc.costD) { System.out.println("Недостаточно средств."); continue; }
            int slot = findEmptyRosterIndex(cs.roster);
            if (slot == -1) {
                System.out.println("Ростер заполнен. 1) Заменить бойца  2) В резерв  0) Отмена");
                int act = readInt(in, "Ваш выбор: ", 0, 2);
                if (act == 0) continue;
                if (act == 1) {
                    printTeam("Кого заменить в ростере?", cs.roster, true);
                    int ridx = readInt(in, "Номер слота (1-" + cs.roster.length + "): ", 1, cs.roster.length) - 1;
                    cs.gulden -= rc.costG; cs.thaler -= rc.costT; cs.ducat -= rc.costD;
                    rc.warrior.teamTag = "[A]";
                    rc.warrior.isRecruited = true;
                    cs.roster[ridx] = rc.warrior;
                    pool.remove(idx - 1);
                    System.out.println("Нанят и заменил слот " + (ridx+1) + ". Остаток: G=" + cs.gulden + ", T=" + cs.thaler + ", D=" + cs.ducat);
                    continue;
                } else {
                    int rslot = findEmptyRosterIndex(cs.reserve);
                    if (rslot == -1) {
                        System.out.println("Резерв заполнен. Уволььте кого-нибудь в меню Резерв.");
                        continue;
                    }
                    cs.gulden -= rc.costG; cs.thaler -= rc.costT; cs.ducat -= rc.costD;
                    rc.warrior.teamTag = "[A]";
                    rc.warrior.isRecruited = true;
                    cs.reserve[rslot] = rc.warrior;
                    pool.remove(idx - 1);
                    System.out.println("Нанят в резерв (слот " + (rslot+1) + "). Остаток: G=" + cs.gulden + ", T=" + cs.thaler + ", D=" + cs.ducat);
                    continue;
                }
            }
            cs.gulden -= rc.costG; cs.thaler -= rc.costT; cs.ducat -= rc.costD;
            rc.warrior.teamTag = "[A]";
            rc.warrior.isRecruited = true;
            cs.roster[slot] = rc.warrior;
            pool.remove(idx - 1);
            System.out.println("Нанят: " + rc.warrior.label() + " в слот " + (slot+1) + ". Осталось: G=" + cs.gulden + ", T=" + cs.thaler + ", D=" + cs.ducat);
        }
    }

    static int countAliveRecruits(Warrior[] arr) {
        int c = 0; if (arr == null) return 0;
        for (Warrior w : arr) if (w != null && w.hp > 0 && w.isRecruited) c++;
        return c;
    }

    static void reserveMenu(Scanner in, CampaignState cs) {
        while (true) {
            System.out.println("\n— РЕЗЕРВ —");
            printTeam("Активный отряд", cs.roster, true);
            printTeam("Резерв", cs.reserve, true);
            System.out.println(" 1) Переместить из резерва в отряд");
            System.out.println(" 2) Переместить из отряда в резерв");
            System.out.println(" 3) Уволить из резерва");
            System.out.println(" 0) Назад");
            int pick = readInt(in, "Ваш выбор: ", 0, 3);
            if (pick == 0) return;
            if (pick == 1) {
                int rIdx = selectAliveWarrior(in, cs.reserve, "Кого перевести из резерва?"); if (rIdx == -1) continue;
                int slot = findEmptyRosterIndex(cs.roster);
                if (slot == -1) { System.out.println("В отряде нет свободного слота."); continue; }
                cs.roster[slot] = cs.reserve[rIdx]; cs.reserve[rIdx] = null; System.out.println("Переведён в слот отряда " + (slot+1));
            } else if (pick == 2) {
                int aIdx = selectAliveWarrior(in, cs.roster, "Кого перевести в резерв?"); if (aIdx == -1) continue;
                int slot = findEmptyRosterIndex(cs.reserve);
                if (slot == -1) { System.out.println("Резерв заполнен (5 слотов). Увольте кого-нибудь."); continue; }
                cs.reserve[slot] = cs.roster[aIdx]; cs.roster[aIdx] = null; System.out.println("Переведён в резерв слот " + (slot+1));
            } else if (pick == 3) {
                int rIdx = selectAliveWarrior(in, cs.reserve, "Кого уволить из резерва?"); if (rIdx == -1) continue;
                System.out.println("Уволен: " + cs.reserve[rIdx].label());
                cs.reserve[rIdx] = null;
            }
        }
    }

    static void marketMenu(Scanner in, CampaignState cs) {
        while (true) {
            System.out.println("\n— РЫНОК —");
            System.out.println("Кошель: Gulden=" + cs.gulden + ", Thaler=" + cs.thaler + ", Ducat=" + cs.ducat);
            System.out.println("Покупка:");
            System.out.println(" 1) Зелье (+1 к запасу) — 15 GULDEN");
            System.out.println(" 2) Латка брони (+1 на следующий бой) — 5 GULDEN");
            System.out.println(" 3) Дешёвая броня (+1 постоянной брони) — 1 THALER");
            System.out.println(" 4) Базовое оружие (PIKE/AXE/SWORD_BUCKLER) — 1 DUCAT");
            System.out.println("Продажа:");
            System.out.println(" 5) Продать зелье (–1 из запаса) +8 GULDEN");
            System.out.println(" 6) Продать латку (–1 из запаса) +3 GULDEN");
            System.out.println(" 0) Назад");
            int pick = readInt(in, "Ваш выбор: ", 0, 6);
            if (pick == 0) return;

            switch (pick) {
                case 1:
                    if (cs.gulden >= 15) { cs.gulden -= 15; cs.stashPotions++; System.out.println("Куплено зелье. В запасе: " + cs.stashPotions); }
                    else System.out.println("Недостаточно Gulden.");
                    break;
                case 2:
                    if (cs.gulden >= 5) { cs.gulden -= 5; cs.stashArmorPatches++; System.out.println("Куплена латка. В запасе: " + cs.stashArmorPatches); }
                    else System.out.println("Недостаточно Gulden.");
                    break;
                case 3:
                    if (cs.thaler >= 1) { cs.thaler -= 1; cs.stashLightArmor++; System.out.println("Куплена дешевая броня. В запасе: " + cs.stashLightArmor); }
                    else System.out.println("Недостаточно Thaler.");
                    break;
                case 4:
                    if (cs.ducat >= 1) { cs.ducat -= 1; cs.stashBasicWeapons++; System.out.println("Куплено базовое оружие. В запасе: " + cs.stashBasicWeapons); }
                    else System.out.println("Недостаточно Ducat.");
                    break;
                case 5:
                    if (cs.stashPotions > 0) { cs.stashPotions--; cs.gulden += 8; System.out.println("Продано зелье. Gulden: " + cs.gulden); }
                    else System.out.println("Нет зелий в запасе.");
                    break;
                case 6:
                    if (cs.stashArmorPatches > 0) { cs.stashArmorPatches--; cs.gulden += 3; System.out.println("Продана латка. Gulden: " + cs.gulden); }
                    else System.out.println("Нет латок в запасе.");
                    break;
            }
        }
    }

    static void equipmentMenu(Scanner in, CampaignState cs) {
        while (true) {
            System.out.println("\n— СНАРЯЖЕНИЕ —");
            System.out.println("Запасы: Potions=" + cs.stashPotions + ", Patches=" + cs.stashArmorPatches +
                    ", LightArmor=" + cs.stashLightArmor + ", BasicWeapons=" + cs.stashBasicWeapons);
            System.out.println(" 1) Выдать зелье бойцу (+1 к личным)");
            System.out.println(" 2) Наложить латку (+1 броня на следующий бой)");
            System.out.println(" 3) Выдать дешевую броню (+1 к броне навсегда)");
            System.out.println(" 4) Выдать базовое оружие (PIKE/AXE/SWORD_BUCKLER)");
            System.out.println(" 0) Назад");
            int pick = readInt(in, "Ваш выбор: ", 0, 4);
            if (pick == 0) return;

            int idx = selectAliveWarrior(in, cs.roster, "Кому выдать?");
            if (idx == -1) continue;
            Warrior target = cs.roster[idx];
            if (target == null) { System.out.println("Пустой слот."); continue; }

            switch (pick) {
                case 1:
                    if (cs.stashPotions > 0) { cs.stashPotions--; target.potions++; System.out.println("Выдано зелье " + target.label()); }
                    else System.out.println("Нет зелий в запасе.");
                    break;
                case 2:
                    if (cs.stashArmorPatches > 0) { cs.stashArmorPatches--; target.tempArmorBonus += 1; System.out.println("Наложена латка: +" + 1 + " к броне на следующий бой."); }
                    else System.out.println("Нет латок.");
                    break;
                case 3:
                    if (cs.stashLightArmor > 0) { cs.stashLightArmor--; target.armor += 1; System.out.println("Выдана дешёвая броня: +" + 1 + " к броне навсегда."); }
                    else System.out.println("Нет дешёвой брони.");
                    break;
                case 4:
                    if (cs.stashBasicWeapons <= 0) { System.out.println("Нет базового оружия."); break; }
                    System.out.println("Выберите оружие: 1) PIKE  2) AXE  3) SWORD_BUCKLER  (внимание: бонусы оружия применяются один раз)");
                    int wPick = readInt(in, "Ваш выбор (1-3): ", 1, 3);
                    Weapon newW = (wPick==1)?Weapon.PIKE : (wPick==2)?Weapon.AXE : Weapon.SWORD_BUCKLER;
                    // Простое правило безопасности: заменяем только если текущее базовое/NONE
                    if (target.weapon == Weapon.NONE || target.weapon == Weapon.PIKE || target.weapon == Weapon.AXE || target.weapon == Weapon.SWORD_BUCKLER) {
                        // Сбрасывать прошлые бонусы мы не умеем в MVP, поэтому меняем только в рамках "базовое на базовое/none"
                        target.weapon = newW;
                        newW.applyTo(target);
                        cs.stashBasicWeapons--;
                        System.out.println("Выдано оружие " + newW + " бойцу " + target.label());
                    } else {
                        System.out.println("Нельзя заменить текущее продвинутое оружие без пересборки статов.");
                    }
                    break;
            }
        }
    }

    static void doExpedition(Scanner in, CampaignState cs) {
        System.out.println("\n— ПОХОД —");
        double roll = Math.random();
        if (roll < 0.5) {
            int found = 10 + (int)(Math.random() * 11); // 10..20
            cs.gulden += found;
            System.out.println("Найдены припасы и контракты: +" + found + " gulden. Теперь: " + cs.gulden);
        } else {
            System.out.println("Дороги пустынны. Без происшествий.");
        }
        cs.day += 1;
    }
    static Warrior[] buildActiveTeam(Warrior[] roster) {
        List<Warrior> active = new ArrayList<>();
        if (roster != null) {
            for (Warrior w : roster) {
                if (w != null && w.hp > 0) {
                    active.add(w);
                }
            }
        }
        return active.toArray(new Warrior[0]);
    }

    static void syncBackToRoster(Warrior[] roster, Warrior[] activeTeam) {
        // Ничего не делаем: объекты Warrior в активной команде — те же ссылки, что и в roster.
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
            RoundOrder ro = promptRoundOrder(in, teamA, teamB);

            if (cs.focusTarget) {
                Integer idx = firstAliveIndex1Based(teamB);
                if (idx != null) { ro.focusEnemyIndexB = idx; System.out.println("🎯 Ротмейстер приказал фокусироваться на враге!"); }
            }

            if (ro.focusEnemyIndexB != null) System.out.println("🎯 Приказ: фокус на B[" + ro.focusEnemyIndexB + "]");

            playTeamRoundRandom(in, round, teamA, teamB, ro);
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
            cs.gulden += reward;
            System.out.println("Награда: +" + reward + " gulden. Всего: " + cs.gulden);

            // опыт победителям
            for (Warrior w : teamA) if (w != null && w.hp > 0) w.onBattleVictory();

            // кошелёк и дроп
            applyVictoryLoot(cs, teamA.length);
        } else {
            int loss = 10 + (int)(Math.random() * 11); // 10..20
            cs.gulden = Math.max(0, cs.gulden - loss);
            System.out.println("Потери: -" + loss + " gulden. Осталось: " + cs.gulden);
        }

        syncBackToRoster(cs.roster, teamA);
        cs.day += 1;
        cs.focusTarget = false; // Сброс фокуса
        // Сброс временных эффектов для отряда
        for (Warrior w : cs.roster) if (w != null) { w.tempArmorBonus = 0; w.battleCryBonus = false; }
    }

    static void applyVictoryLoot(CampaignState cs, int teamSize) {
        int pouch = (5 + (int)(Math.random()*11)) * teamSize; // 5..15 * size
        cs.gulden += pouch;
        System.out.println("🎒 Трофеи: +" + pouch + " gulden в мешочке.");

        int r = (int)(Math.random()*100);
        if (r < 40) {
            cs.stashPotions += 1; System.out.println("🎁 Дроп: зелье (+1 в запас). Всего: " + cs.stashPotions);
        } else if (r < 60) {
            cs.stashArmorPatches += 1; System.out.println("🎁 Дроп: латка брони (+1). Всего: " + cs.stashArmorPatches);
        } else if (r < 75) {
            cs.stashLightArmor += 1; System.out.println("🎁 Дроп: дешёвая броня (+1). Всего: " + cs.stashLightArmor);
        } else if (r < 85) {
            cs.stashBasicWeapons += 1; System.out.println("🎁 Дроп: базовое оружие (+1). Всего: " + cs.stashBasicWeapons);
        } else {
            System.out.println("🎁 Дроп: ничего ценного.");
        }
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

        System.out.print("Сохранить старт боя в СЛОТ? (y/n, default n): ");
        String ansSave = in.nextLine().trim().toLowerCase();
        if (ansSave.equals("y")) {
            System.out.print("Имя сохранения (например, \"Старт боя\"): ");
            String nm = in.nextLine().trim();
            saveGameToNewSlot(nm, teamA, teamB, 1);
        }

        int round = 1;
        while (teamAlive(teamA) && teamAlive(teamB)) {
            RoundOrder ro = promptRoundOrder(in, teamA, teamB);
            if (ro.focusEnemyIndexB != null)
                System.out.println("🎯 Приказ: фокус на B[" + ro.focusEnemyIndexB + "]");

            playTeamRoundRandom(in, round, teamA, teamB, ro);

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

        for (Warrior w : teamA) if (w != null) { w.teamTag = "[A]"; w.nextTurnStance = StanceType.NONE; w.defenseStance = StanceType.NONE; }
        for (Warrior w : teamB) if (w != null) { w.teamTag = "[B]"; w.nextTurnStance = StanceType.NONE; w.defenseStance = StanceType.NONE; }

        printTeam("Команда A (загружено)", teamA);
        printTeam("Команда B (загружено)", teamB);

        while (teamAlive(teamA) && teamAlive(teamB)) {
            RoundOrder ro = promptRoundOrder(in, teamA, teamB);
            if (ro.focusEnemyIndexB != null)
                System.out.println("🎯 Приказ: фокус на B[" + ro.focusEnemyIndexB + "]");

            playTeamRoundRandom(in, round, teamA, teamB, ro);

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

    // ===================== ИГРОВАЯ ЛОГИКА БОЯ =====================
    static void playTeamRoundRandom(Scanner in, int roundNumber, Warrior[] teamA, Warrior[] teamB, RoundOrder ro) {
        log(BRIEF, c(CYAN, "\n🎲 — Раунд " + roundNumber + " — (случайный порядок)"));
        List<Actor> order = buildRandomOrder(teamA, teamB);
        for (Actor act : order) {
            if (!teamAlive(teamA) || !teamAlive(teamB)) break;
            Warrior[] allies  = (act.me.teamTag != null && act.me.teamTag.contains("[A]")) ? teamA : teamB;
            Warrior[] enemies = (allies == teamA) ? teamB : teamA;
            fighterSingleAttack(in, act.me, allies, enemies, ro);
        }
    }

    static List<Actor> buildRandomOrder(Warrior[] teamA, Warrior[] teamB) {
        List<Actor> order = new ArrayList<>();
        for (Warrior w : teamA) if (w != null && w.hp > 0) order.add(new Actor(w, teamB));
        for (Warrior w : teamB) if (w != null && w.hp > 0) order.add(new Actor(w, teamA));
        Collections.shuffle(order);
        return order;
    }

    static int     AGG_DMG_BONUS(Role r)   { return 1 + (r==Role.DUELIST ? 1 : 0); }
    static double  AGG_CRIT_DELTA(Role r)  { return 0.05 + (r==Role.DUELIST ? 0.02 : 0.0); }
    static double  AGG_STUN_DELTA(Role r)  { return 0.05; }
    static int     DEF_ARMOR_BONUS(Role r) { return 1 + (r==Role.TANK ? 1 : 0); }
    static double  DEF_BLOCK_DELTA(Role r) { return 0.05 + (r==Role.SUPPORT ? 0.02 : 0.0); }
    static double  DEF_DODGE_DELTA(Role r) { return 0.05 + (r==Role.SKIRMISHER ? 0.02 : 0.0); }

    static RoundOrder promptRoundOrder(Scanner in, Warrior[] teamA, Warrior[] teamB) {
        RoundOrder ro = new RoundOrder();
        System.out.println("\nПриказ раунда:");
        System.out.println(" 1) Без приказа");
        System.out.println(" 2) Сфокусировать атаку на враге (выбрать из Команды B)");
        int pick = readInt(in, "Ваш выбор (1-2): ", 1, 2);
        if (pick == 2) {
            printTeam("Команда B (для фокуса)", teamB);
            int idx = readInt(in, "Кого фокусим? №: ", 1, teamB.length);
            if (teamB[idx-1] != null && teamB[idx-1].hp > 0) ro.focusEnemyIndexB = idx;
            else System.out.println("Цель недоступна — приказ игнорируется.");
        }
        return ro;
    }

    static void fighterSingleAttack(Scanner in, Warrior attacker, Warrior[] allyTeam, Warrior[] enemyTeam, RoundOrder ro) {
        if (attacker.hp <= 0) return;
        if (!attacker.tryStartTurn()) return;

        boolean isPlayerSide = (attacker.teamTag != null && attacker.teamTag.contains("[A]"));

        if (isPlayerSide) {
            PlayerAction act = promptPlayerAction(in, attacker, allyTeam, enemyTeam);
            switch (act) {
                case POTION_SELF: {
                    if (attacker.potions > 0 && attacker.hp < attacker.maxHp) attacker.usePotion();
                    else System.out.println("Нет зелий или hp полное — действие пропущено.");
                    return;
                }
                case POTION_ALLY: {
                    if (attacker.potions <= 0) { System.out.println("Нет зелий."); return; }
                    int idx = selectAliveAllyIndex(in, allyTeam);
                    if (idx >= 0) attacker.usePotionOn(allyTeam[idx]);
                    return;
                }
                case STANCE_AGGR: {
                    attacker.nextTurnStance = StanceType.AGGRESSIVE;
                    System.out.println("⚔ Стойка: атакующая — эффект на следующий ход " + attacker.label());
                    return;
                }
                case STANCE_DEF: {
                    attacker.defenseStance = StanceType.DEFENSIVE;
                    System.out.println("🛡 Стойка: защитная — действует до следующего хода " + attacker.label());
                    return;
                }
                case ATTACK:
                default:
                    break;
            }
        } else {
            if (attacker.hp <= LOW_HP_THRESHOLD && attacker.potions > 0) {
                if (Math.random() < TEAM_HEAL_CHANCE) { attacker.usePotion(); return; }
            }
        }

        Warrior target = null;
        if (isPlayerSide && ro != null && ro.focusEnemyIndexB != null) {
            int idx = ro.focusEnemyIndexB - 1;
            if (idx >= 0 && idx < enemyTeam.length) {
                Warrior cand = enemyTeam[idx];
                if (cand != null && cand.hp > 0) target = cand;
            }
        }
        if (target == null) target = randomAlive(enemyTeam);

        if (target != null) attacker.attack(target);
    }

    static PlayerAction promptPlayerAction(Scanner in, Warrior attacker, Warrior[] allyTeam, Warrior[] enemyTeam) {
        System.out.println("\nХод " + attacker.label() + ". Выберите действие:");
        System.out.println(" 1) Атаковать");
        System.out.println(" 2) Выпить зелье (сам)");
        System.out.println(" 3) Дать зелье союзнику");
        System.out.println(" 4) Встать в атакующую стойку (эффект на СЛЕД. ход)");
        System.out.println(" 5) Встать в защитную стойку (эффект до след. хода)");
        int pick = readInt(in, "Ваш выбор (1-5): ", 1, 5);
        switch (pick) {
            case 1: return PlayerAction.ATTACK;
            case 2: return PlayerAction.POTION_SELF;
            case 3: return PlayerAction.POTION_ALLY;
            case 4: return PlayerAction.STANCE_AGGR;
            case 5: return PlayerAction.STANCE_DEF;
            default: return PlayerAction.ATTACK;
        }
    }

    private static int selectAliveAllyIndex(Scanner in, Warrior[] allyTeam) {
        List<Integer> aliveIdx = new ArrayList<>();
        System.out.println("Выберите союзника для зелья:");
        for (int i = 0; i < allyTeam.length; i++) {
            Warrior w = allyTeam[i];
            if (w != null && w.hp > 0) {
                aliveIdx.add(i);
                System.out.println(" " + aliveIdx.size() + ") " + w.label() + " (hp=" + w.hp + ")");
            }
        }
        if (aliveIdx.isEmpty()) { System.out.println("Живых союзников нет."); return -1; }
        int pick = readInt(in, "Номер: ", 1, aliveIdx.size());
        return aliveIdx.get(pick - 1);
    }

    // ===================== JSON (старые быстрые сейвы) =====================
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

    // ===================== СЛОТЫ =====================
    static void ensureSavesDir() {
        try { java.nio.file.Files.createDirectories(java.nio.file.Path.of(SAVES_DIR)); } catch (Exception ignored) {}
    }

    static List<SaveMeta> readSaveIndex() {
        ensureSavesDir();
        java.nio.file.Path p = java.nio.file.Path.of(INDEX_PATH);
        if (!java.nio.file.Files.exists(p)) return new ArrayList<>();
        try {
            String json = java.nio.file.Files.readString(p);
            com.google.gson.Gson gson = new com.google.gson.Gson();
            SaveMeta[] arr = gson.fromJson(json, SaveMeta[].class);
            List<SaveMeta> list = new ArrayList<>();
            if (arr != null) Collections.addAll(list, arr);
            list.sort((a,b) -> Long.compare(b.savedAt, a.savedAt));
            return list;
        } catch (Exception e) {
            System.out.println("⚠️ Не удалось прочитать index.json: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    static void writeSaveIndex(List<SaveMeta> metas) {
        ensureSavesDir();
        try {
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
            String json = gson.toJson(metas);
            java.nio.file.Files.writeString(java.nio.file.Path.of(INDEX_PATH), json);
        } catch (Exception e) {
            System.out.println("⚠️ Не удалось записать index.json: " + e.getMessage());
        }
    }

    static String nextSaveId(List<SaveMeta> metas) {
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

    static void saveGameToNewSlot(String saveName, Warrior[] teamA, Warrior[] teamB, int round) {
        ensureSavesDir();
        List<SaveMeta> metas = readSaveIndex();
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

    static List<SaveMeta> listSavesPrint() {
        List<SaveMeta> metas = readSaveIndex();
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
        List<SaveMeta> metas = readSaveIndex();
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

    static void saveCampaignToNewSlot(String saveName, CampaignState cs) {
        ensureSavesDir();
        List<SaveMeta> metas = readSaveIndex();
        String id = nextSaveId(metas);
        String path = SAVES_DIR + "/" + id + ".json";
        long now = System.currentTimeMillis();

        SaveGame sg = new SaveGame(null, null, 0, LOG_LEVEL, COLOR);
        sg.campaign = cs;
        sg.saveName = (saveName == null || saveName.isBlank()) ? id : saveName.trim();
        sg.savedAtEpochMillis = now;

        try {
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
            java.nio.file.Files.writeString(java.nio.file.Path.of(path), gson.toJson(sg));
            metas.add(new SaveMeta(id, sg.saveName, now, path));
            metas.sort((a,b) -> Long.compare(b.savedAt, a.savedAt));
            writeSaveIndex(metas);
            System.out.println("✅ Кампания сохранена в слот: " + id + " — \"" + sg.saveName + "\" (" + fmtTime(now) + ")");
        } catch (Exception e) {
            System.out.println("❌ Ошибка сохранения кампании: " + e.getMessage());
        }
    }

    static CampaignState loadCampaignByNumber(int number) {
        List<SaveMeta> metas = readSaveIndex();
        if (number < 1 || number > metas.size()) {
            System.out.println("Неверный номер слота.");
            return null;
        }
        SaveMeta m = metas.get(number - 1);
        try {
            String json = java.nio.file.Files.readString(java.nio.file.Path.of(m.path));
            com.google.gson.Gson gson = new com.google.gson.Gson();
            SaveGame sg = gson.fromJson(json, SaveGame.class);
            if (sg.campaign == null) {
                System.out.println("⚠️ В выбранном слоте нет кампании (это сейв боя).");
                return null;
            }
            for (Warrior w : sg.campaign.roster) if (w != null) { w.teamTag = "[A]"; w.nextTurnStance = StanceType.NONE; w.defenseStance = StanceType.NONE; }
            System.out.println("✅ Кампания загружена: [" + m.id + "] \"" + sg.saveName + "\" (" + fmtTime(sg.savedAtEpochMillis) + ")");
            return sg.campaign;
        } catch (Exception e) {
            System.out.println("❌ Ошибка загрузки кампании: " + e.getMessage());
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
        for (Warrior w : team) if (w != null && w.hp > 0) return true;
        return false;
    }

    static Warrior randomAlive(Warrior[] team) {
        int alive = 0;
        for (Warrior w : team) if (w != null && w.hp > 0) alive++;
        if (alive == 0) return null;
        int k = (int)(Math.random() * alive);
        for (Warrior w : team) {
            if (w != null && w.hp > 0) {
                if (k == 0) return w;
                k--;
            }
        }
        return null;
    }

    static Integer firstAliveIndex1Based(Warrior[] team) {
        for (int i = 0; i < team.length; i++) {
            Warrior w = team[i];
            if (w != null && w.hp > 0) return i + 1;
        }
        return null;
    }

    static void printTeam(String title, Warrior[] team) {
        printTeam(title, team, false);
    }

    static void printTeam(String title, Warrior[] team, boolean showEmptySlots) {
        System.out.println("\n" + title + ":");
        for (int i = 0; i < team.length; i++) {
            Warrior w = team[i];
            if (w == null) {
                if (showEmptySlots) System.out.println((i + 1) + ") [пусто]");
                continue;
            }
            String nm = String.format("%-14s", w.label());
            System.out.println((i + 1) + ") " + nm
                    + " (hp=" + w.hp + ", atk=" + w.attack
                    + ", arm=" + w.armor + ", pierce=" + w.pierce
                    + ", role=" + w.role + ", weap=" + w.weapon
                    + ", lvl=" + w.level + ", xp=" + w.experience + ")");
        }
    }

    static String teamMiniSummary(Warrior[] teamA, Warrior[] teamB) {
        int aAlive = 0, bAlive = 0, aHp = 0, bHp = 0;
        for (Warrior w : teamA) { if (w != null && w.hp > 0) aAlive++; if (w != null) aHp += w.hp; }
        for (Warrior w : teamB) { if (w != null && w.hp > 0) bAlive++; if (w != null) bHp += w.hp; }
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
                // Именованный ландскнехт
                return Warrior.randomWarriorWithNameExclusions(new HashSet<>() {{ add("__force_type_0"); }});
            }
            case 2: {
                // Именованный швейцарец
                return Warrior.randomWarriorWithNameExclusions(new HashSet<>() {{ add("__force_type_1"); }});
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
        Set<String> used = new HashSet<>();
        list[0] = Warrior.randomWarriorWithNameExclusions(used); used.add(list[0].name);
        list[1] = Warrior.randomWarriorWithNameExclusions(used); used.add(list[1].name);
        for (int i = 2; i < count; i++) { list[i] = Warrior.randomWarriorWithNameExclusions(used); used.add(list[i].name); }

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
        if (aliveIdx.isEmpty()) { System.out.println("Живых союзников нет."); return -1; }
        int pick = readInt(in, "Номер: ", 1, aliveIdx.size());
        return aliveIdx.get(pick - 1);
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

    int armor = 0;
    int pierce = 0;

    int minDamage = 4;
    double missChance  = 0.20;
    double blockChance = 0.15;
    double dodgeChance = 0.10;
    double critChance  = 0.10;
    double stunOnCritChance = 0.25;

    Role role = Role.NONE;
    Weapon weapon = Weapon.NONE;

    // Прокачка
    int level = 1;
    int experience = 0;
    boolean isRotmeister = false;
    boolean battleCryBonus = false;
    int pendingLevelUps = 0;
    boolean isRecruited = false; // используется для лимита наймов

    // Временная броня (латки)
    int tempArmorBonus = 0;

    // Стойки
    StanceType nextTurnStance = StanceType.NONE;
    StanceType defenseStance  = StanceType.NONE;

    Warrior(String name, int hp, int attack) {
        this.name = name; this.hp = hp; this.maxHp = hp; this.attack = attack;
    }

    String label() { return (teamTag == null || teamTag.isEmpty() ? "" : teamTag + " ") + name; }

    // XP/Level
    void gainExperience(int amount) {
        experience += amount;
        System.out.println(name + " получил " + amount + " опыта! (Всего: " + experience + "/" + (100 * level) + ")");
        while (experience >= 100 * level) {
            experience -= 100 * level;
            level++;
            System.out.println("🌟 " + name + " достиг " + level + " уровня!");
            applyLevelUpBonuses();
        }
    }

    void applyLevelUpBonuses() {
        int oldMaxHp = maxHp;
        int oldAtk = attack;
        maxHp += 5;
        attack += 2;
        hp = Math.min(maxHp, hp + 5);
        System.out.println("📈 Бонусы уровня: +5 HP (" + oldMaxHp + "→" + maxHp + "), +2 ATK (" + oldAtk + "→" + attack + ")");
        if (isRotmeister) System.out.println("🎖️ Ротмейстер усилил управление отрядом.");
        pendingLevelUps++; // Добавляем выбор для игрока
    }

    void onBattleVictory() { gainExperience(50); }

    void setRotmeister() {
        if (!isRotmeister) {
            isRotmeister = true;
            role = Role.ROTMEISTER;
            role.applyTo(this);
            System.out.println("🌟 " + name + " теперь Ротмейстер!");
        }
    }

    static Warrior randomWarrior() { return randomWarriorWithNameExclusions(null); }

    // ===== Пулы исторических/правдоподобных имён по типам =====
    static final String[] NAMES_LANDSKNECHT = new String[]{
        "Georg von Frundsberg","Kaspar von Frundsberg","Sebastian Schertlin von Burtenbach","Paul Dolnstein","Peter Hagendorf",
        "Götz von Berlichingen","Franz von Sickingen","Hans Katzianer","Veit von Frundsberg","Sebastian Vogelsberger",
        "Hans Steinmetz","Jörg Eisenfaust","Ulrich Donner","Matthias Sturm","Jakob Reißer","Wolfgang Hackl","Konrad Spieß",
        "Klaus Messer","Dieter Eisenhut","Friedrich Grothmann","Otto Sporer","Albrecht Falkenstein","Martin Grenzhammer",
        "Heinrich Rotbart","Peter Doppelklinge","Ludwig Lange","Bernhard Krause","Niklas Hirt","Till Bleichschmied","Ruprecht Kalkstein"
    };
    static final String[] NAMES_SWISS = new String[]{
        "Hans von Hallwyl","Peter von Luzern","Claus von Uri","Jakob von Zürich",
        "Ueli Gerber","Beat Imhof","Jörg Tukker","Werner Tanner","Reto Landolt","Konrad Gmür","Heinz Rüttimann","Peterli Schmid",
        "Niklaus Aebischer","Matthias Heller","Rudolf Vögeli","Jonas Bärtschi","Christoph Zaugg","Leonhart Vogt","Ulrich Fäh",
        "Jost Amstalden","Werner Gwerder","Melchior Keller","Hansjörg Brunner","Sebastian Künzli","Fritz Oberholzer","Jakob Gessler"
    };
    static final String[] NAMES_SPANIARD = new String[]{
        "Gonzalo Fernández de Córdoba","Pedro Navarro","Antonio de Leyva","Diego García de Paredes","Hernán Cortés","Francisco Pizarro",
        "Pedro de Alvarado","Íñigo López de Loyola","Rodrigo de Mendoza","Martín de Ayala","Alonso de Vera","Juan de Carvajal",
        "Diego de Zúñiga","Baltasar de Rojas","Lope de Villalobos","Esteban de Salazar","Nuño de Cárdenas","Hernando de Sotomayor",
        "Pedro de Tapia","Gaspar de Sandoval","Álvaro de Olivares","Gil de Arriaga","Ramiro de Quintana","Domingo de Peñalosa",
        "Tomás de Barrientos","Luis de Arévalo","Fernando de Valdés","Jaime de Santángel","Sancho de Baeza","Diego de Haro"
    };
    static final String[] NAMES_GALLOWGLASS = new String[]{
        "Domhnall Mac Suibhne","Niall Óg Mac Suibhne","Maolmhuire Mac Suibhne Fánad","Eóin Dubh Mac Suibhne na dTuath",
        "Alasdair Mac Domhnaill","Somhairle Mac Domhnaill","Aodh Mac Cába","Seán Mac Síthigh",
        "Ruaidhrí Mac Suibhne","Tadhg Ruadh Mac Suibhne","Cormac Mac Suibhne Boghaineach","Conall Mac Suibhne","Brian Mac Domhnaill",
        "Aonghus Mac Domhnaill","Lachlann Mac Domhnaill","Alasdair Óg Mac Domhnaill","Donnchadh Mac Dubhghaill","Niall Mac Dubhghaill",
        "Eóghan Mac Ruaidhrí","Toirdhealbhach Mac Ruaidhrí","Eachann Mac Gille Eóin","Fearghal Mac Gille Eóin","Cathal Mac Néill",
        "Áedh Mac Néill","Turlough Mac Cába","Diarmait Mac Cába","Domhnall Mac Síthigh","Cian Mac Síthigh","Seamus Mac Dómhnaill Ghallóglaigh","Murchadh Mac Leòid"
    };
    static final String[] NAMES_REITER = new String[]{
        "Lazarus von Schwendi","Ernst von Mansfeld","Gottfried Heinrich von Pappenheim","Johann von Nassau","Maurice",
        "Heinrich von Schönberg","Wolf von Wallenrodt","Hans von Bredow","Wilhelm von Rantzau","Friedrich von Hohenlohe",
        "Georg von Solms","Albrecht von Witzleben","Kaspar von Wartensleben","Sebastian von Arnim","Ulrich von Wedel","Joachim von Einsiedel",
        "Christoph von der Goltz","Maximilian von Löwenstein","Eitel von Königsmark","Veit von Trotha","Konrad von Plauen",
        "Sigismund von Düring","Balthasar von Schönfeld","Lambert von Krosigk","Ruprecht von Eberstein","Dietrich von Pentz",
        "Hartmann von Lüttichau","Jörg Eisenhart","Hans Schwarzreiter","Klaus Stahlreuter"
    };
    static final String[] NAMES_CONQUISTADOR = new String[]{
        "Hernán Cortés","Francisco Pizarro","Pedro de Alvarado","Diego de Almagro","Vasco Núñez de Balboa","Pánfilo de Narváez",
        "Pedro de Valdivia","Hernando de Soto","Alonso de Ojeda","Juan Ponce de León","Francisco de Orellana","Sebastián de Belalcázar",
        "Álvar Núñez Cabeza de Vaca","Lope de Aguirre","Pedro Menéndez de Avilés","Martín de Ayala","Rodrigo de Barrientos",
        "Gonzalo de Villalobos","Diego de Carvajal","Íñigo de Zorita","Baltasar de Sandoval","Cristóbal de Llerena","Nuño de Castañeda",
        "Tomás de Arriaga","Juan de Zaldívar","Pedro de Mondragón","García López de Cárdenas","Alonso de Cárdenas","Miguel de Legazpi","Juan de Oñate"
    };

    static Warrior randomWarriorWithNameExclusions(java.util.Set<String> used) {
        int t = (int)(Math.random()*6);
        return randomWarriorOfTypeWithExclusions(t, used);
    }

    static Warrior randomWarriorOfTypeWithExclusions(int t, java.util.Set<String> used) {
        String name = null;
        for (int tries = 0; tries < 60 && name == null; tries++) {
            String[] pool = (t==0)?NAMES_LANDSKNECHT:(t==1)?NAMES_SWISS:(t==2)?NAMES_SPANIARD:(t==3)?NAMES_GALLOWGLASS:(t==4)?NAMES_REITER:NAMES_CONQUISTADOR;
            String cand = pool[(int)(Math.random()*pool.length)];
            if (used == null || !used.contains(cand)) name = cand; else t = (t+1)%6; // смена типа, если имя занято
        }
        if (name == null) return randomWarrior();

        // Базовые статы по типу
        Warrior w;
        switch (t) {
            case 0: // Landsknecht
                w = new Warrior(name, 30, 5); w.armor = 2; w.role = Role.TANK; w.weapon = Weapon.ZWEIHANDER; break;
            case 1: // Swiss
                w = new Warrior(name, 25, 6); w.armor = 1; w.role = Role.TANK; w.weapon = (Math.random()<0.7)?Weapon.PIKE:Weapon.SWORD_BUCKLER; break;
            case 2: // Spaniard
                w = new Warrior(name, 24 + (int)(Math.random()*8), 5 + (int)(Math.random()*2)); w.role = Role.DUELIST; w.weapon = Weapon.SWORD_BUCKLER; break;
            case 3: // Gallowglass
                w = new Warrior(name, 28 + (int)(Math.random()*6), 5); w.armor = 1; w.role = Role.TANK; w.weapon = Weapon.AXE; break;
            case 4: // Reiter
                w = new Warrior(name, 24 + (int)(Math.random()*6), 5); w.armor = 1; w.role = Role.SKIRMISHER; w.weapon = Weapon.PISTOL; break;
            default: // Conquistador
                w = new Warrior(name, 26 + (int)(Math.random()*6), 6); w.role = Role.DUELIST; w.weapon = Weapon.SWORD_BUCKLER; break;
        }
        String typePrefix = (t==0)?"Landsknecht":(t==1)?"Swiss":(t==2)?"Spaniard":(t==3)?"Gallowglass":(t==4)?"Reiter":"Conquistador";
        w.name = typePrefix + " " + name;
        w.role.applyTo(w); w.weapon.applyTo(w);
        return w;
    }

    boolean tryStartTurn() {
        if (defenseStance == StanceType.DEFENSIVE) defenseStance = StanceType.NONE;
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

    void usePotionOn(Warrior ally) {
        if (this.potions <= 0 || ally == null || ally.hp <= 0) return;
        int heal = 8;
        int before = ally.hp;
        ally.hp = Math.min(ally.maxHp, ally.hp + heal);
        this.potions--;
        Main.log(Main.BRIEF, "🧪 " + this.label() + " дал зелье " + ally.label() +
                " (+" + Main.c(Main.GREEN, String.valueOf(ally.hp - before)) + " hp). " +
                "У " + this.label() + " осталось зелий: " + this.potions);
    }

    void attack(Warrior enemy) {
        if (Math.random() < missChance) { Main.log(Main.VERBOSE, "🌀 " + label() + " промахнулся по " + enemy.label() + "!"); return; }

        double enemyBlock = enemy.blockChance;
        double enemyDodge = enemy.dodgeChance;
        if (enemy.defenseStance == StanceType.DEFENSIVE) {
            enemyBlock = Role.clamp01(enemyBlock + Main.DEF_BLOCK_DELTA(enemy.role));
            enemyDodge = Role.clamp01(enemyDodge + Main.DEF_DODGE_DELTA(enemy.role));
        }
        if (Math.random() < enemyBlock) { Main.log(Main.VERBOSE, "🛡 " + enemy.label() + " заблокировал удар " + label() + "!"); return; }
        if (Math.random() < enemyDodge) { Main.log(Main.VERBOSE, "💨 " + enemy.label() + " увернулся от удара " + label() + "!"); return; }

        int damage = Math.max(minDamage, this.attack - fatigue);

        double critChanceEff = this.critChance;
        double stunOnCritEff = this.stunOnCritChance;
        int damageBonus = 0;

        if (this.nextTurnStance == StanceType.AGGRESSIVE) {
            damageBonus   += Main.AGG_DMG_BONUS(this.role);
            critChanceEff  = Role.clamp01(critChanceEff + Main.AGG_CRIT_DELTA(this.role));
            stunOnCritEff  = Role.clamp01(stunOnCritEff + Main.AGG_STUN_DELTA(this.role));
            this.nextTurnStance = StanceType.NONE;
        }

        if (battleCryBonus) {
            damageBonus += 1;
            critChanceEff = Role.clamp01(critChanceEff + 0.05);
        }

        damage += damageBonus;

        boolean crit = Math.random() < critChanceEff;
        if (crit) {
            damage *= 2;
            Main.log(Main.BRIEF, "⚡ " + label() + " нанёс " + Main.c(Main.YELLOW, "КРИТИЧЕСКИЙ") + " удар!");
        }

        int enemyArmor = enemy.armor + enemy.tempArmorBonus;
        if (enemy.defenseStance == StanceType.DEFENSIVE) enemyArmor += Main.DEF_ARMOR_BONUS(enemy.role);
        int effectiveArmor = Math.max(0, enemyArmor - this.pierce);
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

        if (crit && Math.random() < stunOnCritEff) {
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

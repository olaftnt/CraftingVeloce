import re

with open("neoforge/src/main/java/com/craftingveloce/crafting/VeloceAutoCrafter.java", "r") as f:
    content = f.read()

# Add FailedAttempt class and variables to Plan
plan_class = """    static final class Plan {
        static final class FailedAttempt {
            long amount;
            long stockId;
            FailedAttempt(long amount, long stockId) {
                this.amount = amount;
                this.stockId = stockId;
            }
        }
        
        long nextStockId = 1;
        long currentStockId = 0;
        final java.util.Map<net.minecraft.world.item.Item, FailedAttempt> failedAmounts = new java.util.HashMap<>();
        
        final List<PlannedRun> runs = new ArrayList<>();"""
content = content.replace("    static final class Plan {\n        final List<PlannedRun> runs = new ArrayList<>();", plan_class)

# Add cache lookup and tracking in plan()
plan_start = """    private static boolean plan(ServerLevel level, VelocePipeNetwork network,
                                Set<Item> enabled,
                                Map<Item, ResourceLocation> preferred,
                                Item item, long amount,
                                Map<Item, Long> stock, Plan plan, Set<Item> visiting, int depth) {
        if (amount <= 0) {
            return true;
        }
        
        Plan.FailedAttempt failure = plan.failedAmounts.get(item);
        if (failure != null && failure.stockId == plan.currentStockId && failure.amount <= amount) {
            return false;
        }"""
content = content.replace("""    private static boolean plan(ServerLevel level, VelocePipeNetwork network,
                                Set<Item> enabled,
                                Map<Item, ResourceLocation> preferred,
                                Item item, long amount,
                                Map<Item, Long> stock, Plan plan, Set<Item> visiting, int depth) {
        if (amount <= 0) {
            return true;
        }""", plan_start)

# Add mutation in plan() Pass 0
pass0 = """            long have = stock.getOrDefault(item, 0L);
            long fromStock = Math.min(have, amount);
            if (fromStock > 0) {
                stock.put(item, have - fromStock);
                plan.currentStockId = plan.nextStockId++;
            }
            long remaining = amount - fromStock;"""
content = content.replace("""            long have = stock.getOrDefault(item, 0L);
            long fromStock = Math.min(have, amount);
            stock.put(item, have - fromStock);
            long remaining = amount - fromStock;""", pass0)

# Add restore in plan() loop
plan_loop = """            // Try successive recipes - the first feasible one wins.
            for (ProcessingEntry recipe : recipes) {
                Map<Item, Long> snapshot = new HashMap<>(stock);
                int planMark = plan.runs.size();
                long snapshotStockId = plan.currentStockId;
                if (planRecipe(level, network, enabled, preferred, recipe, remaining, stock, plan, visiting, depth)) {
                    return true;
                }
                // The recipe did not work out - undo the simulation.
                stock.clear();
                stock.putAll(snapshot);
                plan.rollbackTo(planMark);
                plan.currentStockId = snapshotStockId;
            }
            plan.failedAmounts.put(item, new Plan.FailedAttempt(amount, plan.currentStockId));
            return false;"""
content = content.replace("""            // Try successive recipes - the first feasible one wins.
            for (ProcessingEntry recipe : recipes) {
                Map<Item, Long> snapshot = new HashMap<>(stock);
                int planMark = plan.runs.size();
                if (planRecipe(level, network, enabled, preferred, recipe, remaining, stock, plan, visiting, depth)) {
                    return true;
                }
                // The recipe did not work out - undo the simulation.
                stock.clear();
                stock.putAll(snapshot);
                plan.rollbackTo(planMark);
            }
            return false;""", plan_loop)

# Add mutation in planRecipe() Pass 1
pass1 = """            // Pass 1: take directly from stock if any option is already present in full.
            for (ItemStack opt : options) {
                Item optItem = opt.getItem();
                long avail = stock.getOrDefault(optItem, 0L);
                if (avail >= need) {
                    stock.put(optItem, avail - need);
                    plan.currentStockId = plan.nextStockId++;
                    supplied = true;
                    break;
                }
            }"""
content = content.replace("""            // Pass 1: take directly from stock if any option is already present in full.
            for (ItemStack opt : options) {
                Item optItem = opt.getItem();
                long avail = stock.getOrDefault(optItem, 0L);
                if (avail >= need) {
                    stock.put(optItem, avail - need);
                    supplied = true;
                    break;
                }
            }""", pass1)

# Add mutation in planRecipe() Pass 2
pass2 = """                    long lacking = need - avail;
                    Map<Item, Long> snap2 = new HashMap<>(stock);
                    int mark2 = plan.runs.size();
                    long snap2StockId = plan.currentStockId;
                    if (avail > 0) {
                        stock.put(optItem, 0L);
                        plan.currentStockId = plan.nextStockId++;
                    }
                    if (plan(level, network, enabled, preferred, optItem, lacking, stock, plan, visiting, depth + 1)) {
                        long produced = stock.getOrDefault(optItem, 0L);
                        stock.put(optItem, Math.max(0L, produced - need));
                        plan.currentStockId = plan.nextStockId++;
                        supplied = true;
                        break;
                    }
                    stock.clear();
                    stock.putAll(snap2);
                    plan.rollbackTo(mark2);
                    plan.currentStockId = snap2StockId;"""
content = content.replace("""                    long lacking = need - avail;
                    Map<Item, Long> snap2 = new HashMap<>(stock);
                    int mark2 = plan.runs.size();
                    stock.put(optItem, 0L);
                    if (plan(level, network, enabled, preferred, optItem, lacking, stock, plan, visiting, depth + 1)) {
                        long produced = stock.getOrDefault(optItem, 0L);
                        stock.put(optItem, Math.max(0L, produced - need));
                        supplied = true;
                        break;
                    }
                    stock.clear();
                    stock.putAll(snap2);
                    plan.rollbackTo(mark2);""", pass2)

with open("neoforge/src/main/java/com/craftingveloce/crafting/VeloceAutoCrafter.java", "w") as f:
    f.write(content)

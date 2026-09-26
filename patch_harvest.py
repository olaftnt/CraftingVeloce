import re

with open("neoforge/src/main/java/com/craftingveloce/compat/alchemistry/AlchemistryRecipeHarvest.java", "r") as f:
    content = f.read()

# Add imports
imports = """import com.smashingmods.alchemistry.common.recipe.dissolver.DissolverRecipe;
import com.smashingmods.alchemistry.common.recipe.dissolver.ProbabilityGroup;
import com.smashingmods.alchemistry.common.recipe.dissolver.ProbabilitySet;"""
content = content.replace("import com.smashingmods.alchemistry.common.recipe.fusion.FusionRecipe;", "import com.smashingmods.alchemistry.common.recipe.fusion.FusionRecipe;\n" + imports)

# Add branch in build()
branch = """} else if (type == AlchemistryRecipeFamily.fusion()) {
            for (FusionRecipe recipe : RecipeRegistry.getFusionRecipes(level)) {
                add(out, fusion(recipe));
            }
        } else if (type == AlchemistryRecipeFamily.dissolver()) {
            for (DissolverRecipe recipe : RecipeRegistry.getDissolverRecipes(level)) {
                add(out, dissolver(recipe));
            }
        }"""
content = content.replace("""} else if (type == AlchemistryRecipeFamily.fusion()) {
            for (FusionRecipe recipe : RecipeRegistry.getFusionRecipes(level)) {
                add(out, fusion(recipe));
            }
        }""", branch)

# Add dissolver method
dissolver_method = """    private static ProcessingEntry dissolver(DissolverRecipe recipe) {
        IngredientStack input = recipe.getInput();
        if (input == null || input.isEmpty()) {
            return null;
        }

        // ONLY allow if the input is from chemlib or alchemistry!
        boolean isChemical = false;
        for (ItemStack stack : input.getIngredient().getItems()) {
            ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id != null && (id.getNamespace().equals("chemlib") || id.getNamespace().equals("alchemistry"))) {
                isChemical = true;
                break;
            }
        }
        if (!isChemical) {
            return null;
        }

        ProbabilitySet probSet = recipe.getOutput();
        List<ItemStack> copies = new ArrayList<>();
        List<Float> chances = new ArrayList<>();

        if (probSet != null) {
            boolean weighted = probSet.isWeighted();
            double totalWeight = 0;
            if (weighted) {
                for (ProbabilityGroup group : probSet.getProbabilityGroups()) {
                    totalWeight += group.getProbability();
                }
            }

            for (ProbabilityGroup group : probSet.getProbabilityGroups()) {
                double chance = group.getProbability();
                if (weighted && totalWeight > 0) {
                    chance = chance / totalWeight;
                }
                for (ItemStack result : group.getOutput()) {
                    if (!result.isEmpty()) {
                        copies.add(result.copy());
                        chances.add((float) chance);
                    }
                }
            }
        }

        if (copies.isEmpty()) {
            return null;
        }
        NonNullList<Ingredient> ingredients = NonNullList.withSize(1, Ingredient.EMPTY);
        ingredients.set(0, input.getIngredient());
        return new ProcessingEntry(recipe.getId(), copies, chances, ingredients,
                List.of(Math.max(1, input.getCount())), recipe.getType());
    }"""

content = content.replace("private static ProcessingEntry compactor", dissolver_method + "\n\n    /** Compactor: one ingredient with a count -> one output. */\n    private static ProcessingEntry compactor")

with open("neoforge/src/main/java/com/craftingveloce/compat/alchemistry/AlchemistryRecipeHarvest.java", "w") as f:
    f.write(content)

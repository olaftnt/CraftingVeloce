import json
import os

os.makedirs("assets/craftingveloce/blockstates", exist_ok=True)
os.makedirs("assets/craftingveloce/models/block", exist_ok=True)
os.makedirs("assets/craftingveloce/models/item", exist_ok=True)

blockstate = {
    "variants": {
        "": { "model": "craftingveloce:block/veloce_importer" }
    }
}
with open("assets/craftingveloce/blockstates/veloce_importer.json", "w") as f:
    json.dump(blockstate, f, indent=2)

block_model = {
    "parent": "minecraft:block/cube_all",
    "textures": {
        "all": "craftingveloce:block/veloce_case"
    }
}
with open("assets/craftingveloce/models/block/veloce_importer.json", "w") as f:
    json.dump(block_model, f, indent=2)

item_model = {
    "parent": "craftingveloce:block/veloce_importer"
}
with open("assets/craftingveloce/models/item/veloce_importer.json", "w") as f:
    json.dump(item_model, f, indent=2)

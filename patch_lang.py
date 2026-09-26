import json
import os

with open("assets/craftingveloce/lang/en_us.json", "r") as f:
    lang = json.load(f)

lang["block.craftingveloce.veloce_importer"] = "Veloce Importer"

with open("assets/craftingveloce/lang/en_us.json", "w") as f:
    json.dump(lang, f, indent=2)

if os.path.exists("assets/craftingveloce/lang/pl_pl.json"):
    with open("assets/craftingveloce/lang/pl_pl.json", "r") as f:
        lang_pl = json.load(f)

    lang_pl["block.craftingveloce.veloce_importer"] = "Importer Veloce"

    with open("assets/craftingveloce/lang/pl_pl.json", "w") as f:
        json.dump(lang_pl, f, indent=2)

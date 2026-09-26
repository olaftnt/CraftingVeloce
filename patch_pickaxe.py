import re

with open("data/minecraft/tags/block/mineable/pickaxe.json", "r") as f:
    content = f.read()

content = content.replace('"craftingveloce:veloce_extractor",',
                          '"craftingveloce:veloce_extractor",\n    "craftingveloce:veloce_importer",')

with open("data/minecraft/tags/block/mineable/pickaxe.json", "w") as f:
    f.write(content)

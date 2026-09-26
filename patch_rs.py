import re
with open("neoforge/src/main/java/com/craftingveloce/network/pipe/ConnectedEndpointInfo.java", "r") as f:
    content = f.read()

content = content.replace(
"""        if (type == Type.REFINED_STORAGE) {
            return RefinedStorageHelper.extractItem(level, pos, accessSide,
                    com.craftingveloce.util.VelocePotionMapper.toRealPotion(item, 1), maxCount);
        }""",
"""        if (type == Type.REFINED_STORAGE) {
            return RefinedStorageHelper.extractItem(level, pos, accessSide,
                    requested, maxCount);
        }"""
)

with open("neoforge/src/main/java/com/craftingveloce/network/pipe/ConnectedEndpointInfo.java", "w") as f:
    f.write(content)

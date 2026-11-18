package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.core.program.LayerBlueprint;
import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.aurammods.paracraft.forge.inventory.ItemSelector;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Executes a compact build through the same native placement and navigation as individual placements. */
final class BuildLayersJob extends GameJob {
    private final JsonObject specification;
    private final LayerBlueprint blueprint;
    private final Map<String, Block> blocks = new HashMap<>();
    private int cursor, placed, existing, skipped, consumed;
    private final BlockApproach approach = new BlockApproach();

    BuildLayersJob(UUID id, CompanionPlayer actor, JsonObject specification) {
        super(id, "build_layers", actor);
        this.specification = specification.deepCopy();
        blueprint = new LayerBlueprint(specification);
        for (String name : blueprint.blocks()) {
            if (name.equals(LayerBlueprint.OTHER) || name.equals(LayerBlueprint.UNAVAILABLE)) continue;
            var key = ResourceLocation.tryParse(name);
            if (key == null || !ForgeRegistries.BLOCKS.containsKey(key))
                throw new IllegalArgumentException("Unknown blueprint block " + name);
            blocks.put(name, ForgeRegistries.BLOCKS.getValue(key));
        }
        update();
    }

    @Override protected void advance() {
        var level = actor.serverLevel();
        for (int scanned = 0; cursor < blueprint.size() && scanned < 256; scanned++) {
            var cell = blueprint.cell(cursor);
            var block = blocks.get(cell.block());
            if (block == null || block.defaultBlockState().isAir()) { skipped++; cursor++; continue; }
            var target = cell.position();
            var pos = new BlockPos(target.x(), target.y(), target.z());
            result.add("target", JobJson.position(pos));
            result.addProperty("block", cell.block());
            phase = "building layer " + cell.layer();
            if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos))
                throw new IllegalStateException("Blueprint target is outside world bounds");
            if (level.hasChunkAt(pos) && level.getBlockState(pos).is(block)) {
                stopMoving();
                approach.reset();
                existing++;
                cursor++;
                continue;
            }
            update();
            try {
                if (!NativePlacement.approach(this, approach, pos)) return;
                if (block.asItem() == net.minecraft.world.item.Items.AIR)
                    throw new IllegalStateException("Block " + cell.block() + " has no directly placeable item");
                var item = BuiltInRegistries.ITEM.getKey(block.asItem()).toString();
                consumed += CarriedItems.place(actor, pos, new ItemSelector(item));
                update();
                if (!level.getBlockState(pos).is(block))
                    throw new IllegalStateException("Native placement produced a different block than " + cell.block());
                placed++;
            } catch (BlockApproach.Unreachable noRoute) {
                NativePlacement.skipUnreachable(this, pos);
            }
            cursor++;
            break;
        }
        update();
        if (cursor >= blueprint.size()) NativePlacement.finish(this);
    }

    private void update() {
        result.addProperty("cells", blueprint.size());
        result.addProperty("processed", cursor);
        result.addProperty("placed", placed);
        result.addProperty("alreadyPresent", existing);
        result.addProperty("skipped", skipped);
        result.addProperty("consumed", consumed);
    }

    @Override protected JsonObject writeData() {
        var data = new JsonObject();
        data.add("specification", specification.deepCopy());
        data.addProperty("cursor", cursor);
        data.addProperty("placed", placed);
        data.addProperty("alreadyPresent", existing);
        data.addProperty("skipped", skipped);
        data.addProperty("consumed", consumed);
        return data;
    }

    @Override protected void readData(JsonObject data) {
        cursor = data.get("cursor").getAsInt();
        placed = data.get("placed").getAsInt();
        existing = data.get("alreadyPresent").getAsInt();
        skipped = data.get("skipped").getAsInt();
        consumed = data.get("consumed").getAsInt();
    }
}

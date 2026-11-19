package com.aurammods.paracraft.qa;

import com.aurammods.paracraft.Paracraft;
import com.aurammods.paracraft.core.JobState;
import com.aurammods.paracraft.forge.ServerEvents;
import com.aurammods.paracraft.forge.avatar.*;
import com.aurammods.paracraft.forge.execution.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(Paracraft.ID)
@PrefixGameTestTemplate(false)
public final class EquipmentTransferGameTests {
    @GameTest(batch = "equipment_transfer", template = "arena", timeoutTicks = 80)
    public static void selectsBackpackItemsAndSwapsWholeOffhandStacksWithAFullInventory(GameTestHelper h) {
        var manager = manager(h);
        var entry = spawn(h, manager, UUID.randomUUID(), 48.5);
        var actor = manager.actor(entry.id);
        var inventory = actor.getInventory();
        for (int i = 0; i < inventory.items.size(); i++) inventory.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        inventory.selected = 2;
        var pickaxe = tagged(Items.DIAMOND_PICKAXE, 1, "backpack");
        var torches = tagged(Items.TORCH, 48, "light");
        var shield = tagged(Items.SHIELD, 1, "old-offhand");
        inventory.setItem(12, pickaxe);
        inventory.setItem(18, torches);
        actor.setItemSlot(EquipmentSlot.OFFHAND, shield);
        var work = program(manager, entry, """
                {"steps":[{"op":"equip","item":"minecraft:diamond_pickaxe","slot":"mainhand"},
                          {"op":"equip","item":"minecraft:torch","slot":"offhand"}]}
                """);
        h.onEachTick(() -> {
            h.assertTrue(work.state() != JobState.FAILED, "Hand swaps need no spare inventory slot: " + work.view());
            if (work.state() != JobState.SUCCEEDED) return;
            h.assertTrue(inventory.selected == 2 && ItemStack.isSameItemSameTags(actor.getMainHandItem(), pickaxe)
                    && inventory.getItem(12).is(Items.COBBLESTONE), "The backpack pickaxe replaces the selected hotbar stack");
            h.assertTrue(ItemStack.isSameItemSameTags(actor.getOffhandItem(), torches) && actor.getOffhandItem().getCount() == 48
                    && ItemStack.isSameItemSameTags(inventory.getItem(18), shield) && inventory.getFreeSlot() == -1,
                    "The whole offhand stack and its displaced equipment are conserved in a full inventory");
            clear(manager, entry);
            h.succeed();
        });
    }

    @GameTest(batch="equipment_transfer",template="arena",timeoutTicks=250)
    public static void wornHelmetCanBeUnequippedAndDeliveredWithItsExactMetadata(GameTestHelper h) {
        var m=manager(h);var owner=spawn(h,m,UUID.randomUUID(),60.5);var e=spawn(h,m,owner.id,48.5);var actor=m.actor(e.id);
        var helmet=tagged(Items.DIAMOND_HELMET,1,"worn");helmet.setDamageValue(17);helmet.enchant(Enchantments.ALL_DAMAGE_PROTECTION,3);
        actor.setItemSlot(EquipmentSlot.HEAD,helmet.copy());actor.getInventory().setItem(8,tagged(Items.DIAMOND_HELMET,1,"spare"));
        var job=program(m,e,"{\"steps\":[{\"op\":\"unequip\",\"slot\":\"head\",\"saveAs\":\"removed\"},{\"op\":\"deliver_items\",\"itemsFrom\":\"removed\",\"recipient\":{\"playerUuid\":\""+owner.id+"\"}}]}");
        h.onEachTick(()->{h.assertTrue(job.state()!=JobState.FAILED,"Helmet delivery failed: "+job.view());if(job.state()!=JobState.SUCCEEDED)return;
            h.assertTrue(actor.getItemBySlot(EquipmentSlot.HEAD).isEmpty() && actor.getInventory().countItem(Items.DIAMOND_HELMET)==1 && actor.getInventory().getItem(8).getTag().getString("qa_gear").equals("spare"),"Only the worn helmet was unequipped and given away");
            var receiver=m.actor(owner.id);boolean delivered=receiver.getInventory().items.stream().anyMatch(stack->ItemStack.isSameItemSameTags(stack,helmet))
                    || h.getLevel().getEntitiesOfClass(ItemEntity.class,receiver.getBoundingBox().inflate(3)).stream().anyMatch(item->ItemStack.isSameItemSameTags(item.getItem(),helmet) && assigned(item,owner.id));
            h.assertTrue(delivered && actor.position().distanceTo(h.absoluteVec(new Vec3(48.5,2,48.5)))>5,"Existing worn gear's tags, damage and enchantments reach the recipient through real movement");
            clear(m,e);clear(m,owner);pass("unequip_worn_helmet_native_metadata_delivery");h.succeed();});
    }
    @GameTest(batch="equipment_transfer",template="arena",timeoutTicks=200)
    public static void fullInventoryPreventsUnequipButAllowsDroppingDirectlyFromEquipment(GameTestHelper h) {
        var m=manager(h);var owner=spawn(h,m,UUID.randomUUID(),55.5);var e=spawn(h,m,owner.id,48.5);var actor=m.actor(e.id);
        for(int i=0;i<36;i++)actor.getInventory().setItem(i,new ItemStack(Items.COBBLESTONE,64));var helmet=tagged(Items.DIAMOND_HELMET,1,"full_inventory");actor.setItemSlot(EquipmentSlot.HEAD,helmet.copy());
        var unequip=new UnequipJob(UUID.randomUUID(),actor,"head");unequip.tick();
        h.assertTrue(unequip.state()==JobState.FAILED && ItemStack.isSameItemSameTags(actor.getItemBySlot(EquipmentSlot.HEAD),helmet) && actor.getInventory().countItem(Items.COBBLESTONE)==2304,"Full inventory refuses unequip without changing equipment or belongings");
        var job=program(m,e,"{\"steps\":[{\"op\":\"drop_items\",\"item\":\"minecraft:diamond_helmet\",\"quantity\":1,\"slot\":\"head\",\"recipient\":{\"playerUuid\":\""+owner.id+"\"},\"saveAs\":\"given\"}]}");
        h.onEachTick(()->{h.assertTrue(job.state()!=JobState.FAILED,"Direct worn drop failed: "+job.view());if(job.state()!=JobState.SUCCEEDED)return;
            h.assertTrue(actor.getItemBySlot(EquipmentSlot.HEAD).isEmpty() && actor.getInventory().countItem(Items.COBBLESTONE)==2304,"Dropping directly from equipment needs no inventory capacity");
            h.assertTrue(job.result().getAsJsonObject("results").getAsJsonObject("given").get("dropped").getAsInt()==1,"Exactly one worn item was actually dropped");clear(m,e);clear(m,owner);pass("drop_equipped_helmet_full_inventory");h.succeed();});
    }
    @GameTest(batch="equipment_transfer",template="arena")
    public static void nativeBindingRestrictionBlocksBothRemovalActions(GameTestHelper h) {
        var m=manager(h);var e=spawn(h,m,UUID.randomUUID(),48.5);var actor=m.actor(e.id);var helmet=tagged(Items.DIAMOND_HELMET,1,"binding");helmet.enchant(Enchantments.BINDING_CURSE,1);actor.setItemSlot(EquipmentSlot.HEAD,helmet.copy());
        var unequip=new UnequipJob(UUID.randomUUID(),actor,"head");unequip.tick();var drop=new DropItemsJob(UUID.randomUUID(),actor,json("{\"op\":\"drop_items\",\"item\":\"minecraft:diamond_helmet\",\"quantity\":1,\"slot\":\"head\"}"));drop.tick();
        h.assertTrue(unequip.state()==JobState.FAILED && drop.state()==JobState.FAILED && ItemStack.isSameItemSameTags(actor.getItemBySlot(EquipmentSlot.HEAD),helmet),"Native armor-slot restrictions apply to unequip and equipped drops");
        h.assertTrue(actor.getInventory().countItem(Items.DIAMOND_HELMET)==1 && entities(h,actor).isEmpty(),"Binding removal failures create neither duplicate inventory items nor drops");clear(m,e);pass("unequip_drop_native_binding_restriction");h.succeed();
    }
    @GameTest(batch="equipment_transfer",template="arena")
    public static void cancelledTossAndRefusedWorldEntityRestoreTheExactStack(GameTestHelper h) {
        var m=manager(h);var e=spawn(h,m,UUID.randomUUID(),48.5);var actor=m.actor(e.id);var stack=tagged(Items.DIAMOND,7,"refused");actor.getInventory().setItem(0,stack.copy());int[] tosses={0};
        class Deny {@SubscribeEvent public void toss(ItemTossEvent event){if(event.getPlayer()==actor){tosses[0]++;event.setCanceled(true);}}}
        var deny=new Deny();MinecraftForge.EVENT_BUS.register(deny);var spec=json("{\"op\":\"drop_items\",\"item\":\"minecraft:diamond\",\"quantity\":7}");
        try {var drop=new DropItemsJob(UUID.randomUUID(),actor,spec);drop.tick();h.assertTrue(drop.state()==JobState.FAILED && tosses[0]==1,"Real native toss hook can cancel the action");}
        finally {MinecraftForge.EVENT_BUS.unregister(deny);}
        h.assertTrue(ItemStack.matches(actor.getInventory().getItem(0),stack) && entities(h,actor).isEmpty(),"Cancelled toss returns the exact original count and metadata");
        class Refund {@SubscribeEvent public void toss(ItemTossEvent event){if(event.getPlayer()==actor){actor.getInventory().placeItemBackInInventory(event.getEntity().getItem().copy());event.setCanceled(true);}}}
        var refund=new Refund();MinecraftForge.EVENT_BUS.register(refund);
        try {var drop=new DropItemsJob(UUID.randomUUID(),actor,spec);drop.tick();h.assertTrue(drop.state()==JobState.FAILED,"A native hook may cancel and refund its own item");}
        finally {MinecraftForge.EVENT_BUS.unregister(refund);}
        h.assertTrue(actor.getInventory().countItem(Items.DIAMOND)==7 && ItemStack.matches(actor.getInventory().getItem(0),stack),"A hook's existing refund is not duplicated");
        class Refuse {@SubscribeEvent public void join(net.minecraftforge.event.entity.EntityJoinLevelEvent event){if(event.getEntity() instanceof ItemEntity item && ItemStack.isSameItemSameTags(item.getItem(),stack))event.setCanceled(true);}}
        var refuse=new Refuse();MinecraftForge.EVENT_BUS.register(refuse);
        try {var drop=new DropItemsJob(UUID.randomUUID(),actor,spec);drop.tick();h.assertTrue(drop.state()==JobState.FAILED,"Actual world entity refusal is detected after native toss");}
        finally {MinecraftForge.EVENT_BUS.unregister(refuse);}
        h.assertTrue(ItemStack.matches(actor.getInventory().getItem(0),stack) && entities(h,actor).isEmpty(),"World refusal also preserves items without granting a drop");clear(m,e);pass("drop_native_toss_and_world_refusal_rollback");h.succeed();
    }
    @GameTest(batch="equipment_transfer",template="arena")
    public static void partialDropCheckpointRestoresWithoutRepeatingEarlierDrops(GameTestHelper h) {
        var m=manager(h);var e=spawn(h,m,UUID.randomUUID(),48.5);var actor=m.actor(e.id);actor.getInventory().setItem(0,tagged(Items.IRON_INGOT,64,"first"));actor.getInventory().setItem(1,tagged(Items.IRON_INGOT,7,"second"));
        var drop=new DropItemsJob(UUID.randomUUID(),actor,json("{\"op\":\"drop_items\",\"item\":\"minecraft:iron_ingot\",\"quantity\":70}"));drop.tick();drop.pause();var saved=drop.checkpoint();
        h.assertTrue(drop.state()==JobState.PAUSED && drop.result().get("dropped").getAsInt()==64,"Drop is incremental and records the first actual stack before pausing");
        m.disconnectOwner(e.authority.owner());m.restoreOwner(e.authority.owner());actor=m.actor(e.id);var resumed=JobCodec.restore(actor,saved);resumed.resume();resumed.tick();
        h.assertTrue(resumed.state()==JobState.SUCCEEDED && resumed.result().get("dropped").getAsInt()==70 && actor.getInventory().countItem(Items.IRON_INGOT)==1,"Rebound checkpoint drops only six remaining items, leaving one carried");
        int actual=entities(h,actor).stream().filter(item->item.getItem().is(Items.IRON_INGOT)).mapToInt(item->item.getItem().getCount()).sum();
        h.assertTrue(actual==70 && resumed.result().getAsJsonArray("itemEntities").size()==2,"Earlier physical drops are not replayed on resume");clear(m,e);pass("drop_partial_checkpoint_native_rebind_no_replay");h.succeed();
    }
    @GameTest(batch="equipment_transfer",template="arena")
    public static void unequipHandsAndDropAllPreserveNativeHookMetadata(GameTestHelper h) {
        var m=manager(h);var e=spawn(h,m,UUID.randomUUID(),48.5);var actor=m.actor(e.id);actor.getInventory().setItem(0,tagged(Items.DIAMOND,12,"hand"));
        var hand=new UnequipJob(UUID.randomUUID(),actor,"mainhand");hand.tick();h.assertTrue(hand.state()==JobState.SUCCEEDED && actor.getMainHandItem().isEmpty() && actor.getInventory().countItem(Items.DIAMOND)==12,"Unequipping mainhand clears the selected slot without losing its stack");
        actor.setItemSlot(EquipmentSlot.OFFHAND,tagged(Items.TORCH,5,"offhand"));var off=new UnequipJob(UUID.randomUUID(),actor,"offhand");off.tick();h.assertTrue(off.state()==JobState.SUCCEEDED && actor.getOffhandItem().isEmpty(),"Offhand can be moved into main inventory");
        actor.getInventory().setItem(5,tagged(Items.TORCH,20,"inventory"));actor.setItemSlot(EquipmentSlot.OFFHAND,tagged(Items.TORCH,3,"still_offhand"));
        class Mark {@SubscribeEvent public void toss(ItemTossEvent event){if(event.getPlayer()==actor)event.getEntity().getItem().getOrCreateTag().putString("qa_toss","native");}}
        var mark=new Mark();MinecraftForge.EVENT_BUS.register(mark);var drop=new DropItemsJob(UUID.randomUUID(),actor,json("{\"op\":\"drop_items\",\"item\":\"minecraft:torch\",\"quantity\":\"all\"}"));
        try {for(int i=0;i<5 && !drop.state().terminal();i++)drop.tick();}finally{MinecraftForge.EVENT_BUS.unregister(mark);}
        h.assertTrue(drop.state()==JobState.SUCCEEDED && drop.result().get("dropped").getAsInt()==28 && actor.getInventory().countItem(Items.TORCH)==0,"Drop all includes carried inventory and offhand with actual counts");
        h.assertTrue(JobJson.stacks(drop.result().getAsJsonArray("droppedStacks")).stream().allMatch(stack->stack.getTag().getString("qa_toss").equals("native")),"Results preserve actual post-toss-hook stack metadata");
        clear(m,e);pass("unequip_hands_drop_all_native_hook_metadata");h.succeed();
    }
    @GameTest(batch="equipment_transfer",template="arena")
    public static void shortagesRetainActualPartialResultsAndUnavailableRecipientsKeepItems(GameTestHelper h) {
        var m=manager(h);var e=spawn(h,m,UUID.randomUUID(),48.5);var actor=m.actor(e.id);actor.getInventory().setItem(0,new ItemStack(Items.GOLD_INGOT,2));
        var missing=new DropItemsJob(UUID.randomUUID(),actor,json("{\"op\":\"drop_items\",\"item\":\"minecraft:gold_ingot\",\"quantity\":5}"));missing.tick();missing.tick();
        h.assertTrue(missing.state()==JobState.FAILED && missing.result().get("dropped").getAsInt()==2 && actor.getInventory().countItem(Items.GOLD_INGOT)==0,"Short supply reports two completed physical drops and failure for the rest");
        actor.getInventory().setItem(0,new ItemStack(Items.DIAMOND,3));var unavailable=new DropItemsJob(UUID.randomUUID(),actor,json("{\"op\":\"drop_items\",\"item\":\"minecraft:diamond\",\"quantity\":3,\"recipient\":{\"playerUuid\":\""+UUID.randomUUID()+"\"}}"));unavailable.tick();
        h.assertTrue(unavailable.state()==JobState.FAILED && actor.getInventory().countItem(Items.DIAMOND)==3,"An unavailable recipient does not consume items");clear(m,e);pass("drop_short_supply_partial_and_missing_recipient");h.succeed();
    }
    private static AvatarManager manager(GameTestHelper h){return ServerEvents.avatars(h.getLevel().getServer());}
    private static AvatarEntry spawn(GameTestHelper h,AvatarManager m,UUID owner,double x){return m.spawn(owner,h.getLevel(),h.absoluteVec(new Vec3(x,2,48.5)),"QG"+UUID.randomUUID().toString().substring(0,8));}
    private static JsonObject json(String value){return JsonParser.parseString(value).getAsJsonObject();}
    private static GameJob program(AvatarManager m,AvatarEntry e,String value){return m.jobs().submitProgram(e.authority.owner(),e.id,e.authority.session(),UUID.randomUUID(),json(value),"replace");}
    private static ItemStack tagged(Item item,int count,String marker){var stack=new ItemStack(item,count);stack.getOrCreateTag().putString("qa_gear",marker);return stack;}
    private static java.util.List<ItemEntity> entities(GameTestHelper h,CompanionPlayer actor){return h.getLevel().getEntitiesOfClass(ItemEntity.class,actor.getBoundingBox().inflate(16));}
    private static boolean assigned(ItemEntity entity,UUID owner){var tag=entity.saveWithoutId(new CompoundTag());return tag.hasUUID("Owner") && tag.getUUID("Owner").equals(owner);}
    private static void clear(AvatarManager m,AvatarEntry e){m.actor(e.id).getInventory().clearContent();m.destroy(e.authority.owner(),e.id);}
    private static void pass(String event){Paracraft.LOG.info("QA_EVT {\"event\":\"{}\",\"status\":\"pass\"}",event);}
}

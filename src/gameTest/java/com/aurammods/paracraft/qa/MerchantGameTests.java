package com.aurammods.paracraft.qa;

import com.aurammods.paracraft.Paracraft;
import com.aurammods.paracraft.core.JobState;
import com.aurammods.paracraft.forge.ServerEvents;
import com.aurammods.paracraft.forge.avatar.*;
import com.aurammods.paracraft.forge.execution.*;
import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.TradeWithVillagerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(Paracraft.ID)
@PrefixGameTestTemplate(false)
public final class MerchantGameTests {
    @GameTest(batch="trading",template="arena",timeoutTicks=350)
    public static void idleNeverTradesAndInspectionWalksToNativeDiscountedOffers(GameTestHelper h){
        var f=new Fixture(h,14);var offer=offer(10,2,new ItemStack(Items.BOOK),5);offer.getResult().getOrCreateTag().putString("trade_marker","real_offer");f.villager.getOffers().add(offer);
        f.actor.getInventory().setItem(0,new ItemStack(Items.EMERALD,20));f.actor.getInventory().setItem(1,new ItemStack(Items.PAPER,4));f.actor.addEffect(new MobEffectInstance(MobEffects.HERO_OF_THE_VILLAGE,600,0));
        Vec3 start=f.actor.position();GameJob[] work={null};h.runAfterDelay(70,()->{
            h.assertTrue(offer.getUses()==0 && f.actor.getInventory().countItem(Items.EMERALD)==20,"No automatic idle purchases occur");
            work[0]=f.submit("{\"steps\":[{\"op\":\"inspect_trades\",\"target\":\""+f.villager.getUUID()+"\",\"saveAs\":\"offers\"}]}");
        });
        h.onEachTick(()->{
            if(work[0]==null)return;check(h,work[0]);if(work[0].state()!=JobState.SUCCEEDED)return;
            var result=work[0].result().getAsJsonObject("results").getAsJsonObject("offers");var seen=result.getAsJsonArray("offers").get(0).getAsJsonObject();
            h.assertTrue(f.actor.position().distanceTo(start)>7 && seen.getAsJsonObject("costA").get("count").getAsInt()==7,"Inspection walks and observes the avatar's native hero discount");
            h.assertTrue(seen.getAsJsonObject("costB").get("count").getAsInt()==2 && seen.getAsJsonObject("result").get("snbt").getAsString().contains("real_offer") && seen.get("canAfford").getAsBoolean(),"Both prices, output NBT, and actual affordability are visible");
            h.assertTrue(seen.get("offerId").getAsString().length()==64 && offer.getUses()==0 && f.actor.getInventory().countItem(Items.EMERALD)==20 && f.villager.getTradingPlayer()==null && f.actor.containerMenu==f.actor.inventoryMenu,"Inspection closes its native menu and consumes nothing");
            var snapshot=f.m.control().snapshot(f.owner,f.entry.id);h.assertTrue(snapshot.getAsJsonArray("operations").asList().stream().anyMatch(x->x.getAsString().equals("trade")) && snapshot.getAsJsonArray("nearbyEntities").asList().stream().anyMatch(x->x.getAsJsonObject().has("merchant")),"Real snapshots advertise merchant targets and actions");
            f.close();pass("merchant_inspect_navigation_discount_nbt_no_auto_trade");h.succeed();
        });
    }
    @GameTest(batch="trading",template="arena",timeoutTicks=400)
    public static void twoPaymentTradesFireNativeHooksAndDeliverOnlyNewOutputs(GameTestHelper h){
        var f=new Fixture(h,8);var offer=offer(2,1,new ItemStack(Items.BOOK),6);f.villager.getOffers().add(offer);
        f.actor.getInventory().setItem(0,new ItemStack(Items.EMERALD,4));f.actor.getInventory().setItem(40,new ItemStack(Items.PAPER,2));var old=new ItemStack(Items.BOOK,3);old.getOrCreateTag().putString("source","old");f.actor.getInventory().setItem(1,old);
        var recipient=f.m.spawn(UUID.randomUUID(),h.getLevel(),h.absoluteVec(new Vec3(3.5,2,10.5)),name());int[] events={0};
        class Hook{@SubscribeEvent public void traded(TradeWithVillagerEvent e){if(e.getEntity()==f.actor){events[0]++;f.actor.containerMenu.getCarried().getOrCreateTag().putString("source","native_trade_hook");}}}var hook=new Hook();MinecraftForge.EVENT_BUS.register(hook);
        var work=f.submit("{\"steps\":["+f.trade(offer,2)+",{\"op\":\"deliver_items\",\"itemsFrom\":\"loot\",\"recipient\":{\"playerUuid\":\""+recipient.id+"\"},\"saveAs\":\"delivery\"}]}");
        h.onEachTick(()->{
            check(h,work);if(work.state()!=JobState.SUCCEEDED)return;MinecraftForge.EVENT_BUS.unregister(hook);
            var loot=work.result().getAsJsonObject("results").getAsJsonObject("loot");
            h.assertTrue(events[0]==2 && offer.getUses()==2 && f.villager.getVillagerXp()==4 && f.actor.getStats().getValue(Stats.CUSTOM.get(Stats.TRADED_WITH_VILLAGER))==2,"Each trade uses native stock, XP, stats and Forge hooks exactly once");
            h.assertTrue(f.actor.getInventory().countItem(Items.EMERALD)==0 && f.actor.getInventory().getItem(40).isEmpty() && f.actor.getInventory().countItem(Items.BOOK)==3,"Both real payments including offhand were consumed and preexisting books retained");
            h.assertTrue(loot.get("trades").getAsInt()==2 && loot.get("acquired").getAsInt()==2 && loot.getAsJsonArray("acquiredStacks").toString().contains("native_trade_hook") && work.result().getAsJsonObject("results").getAsJsonObject("delivery").get("delivered").getAsInt()==2,"Exact post-hook output ledger composes with delivery");
            f.m.actor(recipient.id).getInventory().clearContent();f.m.destroy(recipient.authority.owner(),recipient.id);f.close();pass("merchant_native_two_payments_offhand_xp_hooks_delivery");h.succeed();
        });
    }
    @GameTest(batch="trading",template="arena",timeoutTicks=100)
    public static void exhaustedBatchPreservesPartialResultsAndEntersCatch(GameTestHelper h){
        var f=new Fixture(h,5);var offer=offer(1,0,new ItemStack(Items.BREAD,3),2);f.villager.getOffers().add(offer);f.actor.getInventory().setItem(0,new ItemStack(Items.EMERALD,8));
        var work=f.submit("{\"steps\":[{\"try\":["+f.trade(offer,4)+"],\"catch\":[{\"op\":\"report_failure\",\"saveAs\":\"caught\"}]}]}");
        h.onEachTick(()->{check(h,work);if(work.state()!=JobState.SUCCEEDED)return;var result=work.result().getAsJsonObject("results");
            h.assertTrue(offer.getUses()==2 && f.actor.getInventory().countItem(Items.EMERALD)==6 && f.actor.getInventory().countItem(Items.BREAD)==6,"Only two available trades ran");
            h.assertTrue(result.getAsJsonObject("loot").get("trades").getAsInt()==2 && result.getAsJsonObject("loot").get("acquired").getAsInt()==6 && result.getAsJsonObject("caught").toString().contains("out of stock"),"Catch receives actual failure and partial output remains available");f.close();pass("merchant_stock_partial_failure_catch");h.succeed();});
    }
    @GameTest(batch="trading",template="arena",timeoutTicks=50)
    public static void actualOfferNbtAndSharedPaymentCountsAreRequired(GameTestHelper h){
        var f=new Fixture(h,5);var required=new ItemStack(Items.PAPER,2);required.getOrCreateTag().putString("required","yes");var offer=new MerchantOffer(required,new ItemStack(Items.EMERALD),new ItemStack(Items.BOOK),5,2,0);f.villager.getOffers().add(offer);
        f.actor.getInventory().setItem(0,new ItemStack(Items.PAPER,2));f.actor.getInventory().setItem(1,new ItemStack(Items.EMERALD));var failed=f.direct(f.trade(offer,1));failed.tick();
        h.assertTrue(failed.state()==JobState.FAILED && offer.getUses()==0 && f.actor.getInventory().countItem(Items.PAPER)==2 && f.actor.getInventory().countItem(Items.EMERALD)==1,"Missing required NBT cannot purchase or consume either payment");
        var matching=required.copy();matching.getOrCreateTag().putString("extra","allowed");f.actor.getInventory().setItem(0,matching);var accepted=f.direct(f.trade(offer,1));accepted.tick();
        h.assertTrue(accepted.state()==JobState.SUCCEEDED && offer.getUses()==1,"Native NBT subset matching accepts additional carried metadata");
        var same= new MerchantOffer(new ItemStack(Items.EMERALD,2),new ItemStack(Items.EMERALD,2),new ItemStack(Items.DIAMOND),5,2,0);f.villager.getOffers().clear();f.villager.getOffers().add(same);f.actor.getInventory().setItem(0,new ItemStack(Items.EMERALD,3));
        var shortfall=f.direct(f.trade(same,1));shortfall.tick();h.assertTrue(shortfall.state()==JobState.FAILED && same.getUses()==0 && f.actor.getInventory().countItem(Items.EMERALD)==3,"Two same-item payments cannot spend the same carried stack twice");
        f.actor.getInventory().setItem(40,new ItemStack(Items.EMERALD));var combined=f.direct(f.trade(same,1));combined.tick();h.assertTrue(combined.state()==JobState.SUCCEEDED && same.getUses()==1 && f.actor.getInventory().countItem(Items.EMERALD)==0,"Main and offhand sources jointly pay both same-item costs");
        f.close();pass("merchant_nbt_subset_and_shared_payment_conservation");h.succeed();
    }
    @GameTest(batch="trading",template="arena",timeoutTicks=50)
    public static void nativeInteractionCancellationAndBusyMerchantAreRespected(GameTestHelper h){
        var f=new Fixture(h,5);var offer=offer(1,0,new ItemStack(Items.BOOK),5);f.villager.getOffers().add(offer);f.actor.getInventory().setItem(0,new ItemStack(Items.EMERALD,4));int[] ordinary={0};
        class Specific{@SubscribeEvent public void deny(PlayerInteractEvent.EntityInteractSpecific e){if(e.getEntity()==f.actor){e.setCanceled(true);e.setCancellationResult(InteractionResult.PASS);}}@SubscribeEvent public void ordinary(PlayerInteractEvent.EntityInteract e){if(e.getEntity()==f.actor)ordinary[0]++;}}var specific=new Specific();MinecraftForge.EVENT_BUS.register(specific);
        try{var blocked=f.direct(f.trade(offer,1));blocked.tick();h.assertTrue(blocked.state()==JobState.FAILED && ordinary[0]==0,"Specific-hook cancellation never falls through to ordinary interaction");}finally{MinecraftForge.EVENT_BUS.unregister(specific);}
        class Ordinary{@SubscribeEvent public void deny(PlayerInteractEvent.EntityInteract e){if(e.getEntity()==f.actor){e.setCanceled(true);e.setCancellationResult(InteractionResult.FAIL);}}}var normal=new Ordinary();MinecraftForge.EVENT_BUS.register(normal);
        try{var blocked=f.direct(f.trade(offer,1));blocked.tick();h.assertTrue(blocked.state()==JobState.FAILED,"Ordinary interaction protection refuses trading");}finally{MinecraftForge.EVENT_BUS.unregister(normal);}
        var other=f.m.spawn(UUID.randomUUID(),h.getLevel(),h.absoluteVec(new Vec3(7.5,2,5.5)),name());f.villager.setTradingPlayer(f.m.actor(other.id));var busy=f.direct(f.trade(offer,1));busy.tick();
        h.assertTrue(busy.state()==JobState.FAILED && f.villager.getTradingPlayer()==f.m.actor(other.id) && offer.getUses()==0 && f.actor.getInventory().countItem(Items.EMERALD)==4,"A busy merchant is never stolen and denied trades consume nothing");
        f.villager.setTradingPlayer(null);f.m.destroy(other.authority.owner(),other.id);f.close();pass("merchant_native_interaction_protection_busy");h.succeed();
    }
    @GameTest(batch="trading",template="arena",timeoutTicks=180)
    public static void pauseRestoreAndRepeatedRequestNeverRepeatCompletedTrades(GameTestHelper h){
        var f=new Fixture(h,5);var offer=offer(1,0,new ItemStack(Items.BOOK),8);f.villager.getOffers().add(offer);f.actor.getInventory().setItem(0,new ItemStack(Items.EMERALD,6));
        var program=json("{\"steps\":["+f.trade(offer,3)+"]}");UUID id=UUID.randomUUID();GameJob[] work={f.m.jobs().submitProgram(f.owner,f.entry.id,f.entry.authority.session(),id,program,"replace")};boolean[] restored={false};
        h.onEachTick(()->{check(h,work[0]);
            if(!restored[0] && offer.getUses()==1){
                f.m.jobs().pause(f.entry.id);h.assertTrue(f.villager.getTradingPlayer()==null && f.actor.containerMenu==f.actor.inventoryMenu,"Pausing retains no payment or merchant lock");
                f.m.disconnectOwner(f.owner);h.assertTrue(f.m.restoreOwner(f.owner).isEmpty(),"Saved trading body restores");work[0]=f.m.jobs().request(f.entry.id,id);f.m.jobs().resumeRequest(f.owner,f.entry.id,f.entry.authority.session(),id);restored[0]=true;return;
            }
            if(work[0].state()!=JobState.SUCCEEDED)return;var actor=f.m.actor(f.entry.id);h.assertTrue(restored[0] && offer.getUses()==3 && actor.getInventory().countItem(Items.EMERALD)==3 && actor.getInventory().countItem(Items.BOOK)==3,"Exactly the two remaining native trades ran after restoration");
            var same=f.m.jobs().submitProgram(f.owner,f.entry.id,f.entry.authority.session(),id,program,"replace");h.assertTrue(same==work[0] && offer.getUses()==3,"Repeated request returns its completed receipt without trading again");
            f.close();pass("merchant_checkpoint_resume_request_dedup");h.succeed();
        });
    }
    @GameTest(batch="trading",template="arena",timeoutTicks=50)
    public static void nativeFullInventoryOverflowIsConservedAndReported(GameTestHelper h){
        var f=new Fixture(h,5);var offer=offer(1,0,new ItemStack(Items.DIAMOND,3),5);f.villager.getOffers().add(offer);
        for(int i=0;i<36;i++)f.actor.getInventory().setItem(i,new ItemStack(Items.COBBLESTONE,64));f.actor.getInventory().setItem(0,new ItemStack(Items.EMERALD,64));var work=f.direct(f.trade(offer,3));work.tick();
        int dropped=h.getLevel().getEntitiesOfClass(ItemEntity.class,f.actor.getBoundingBox().inflate(8),e->e.getItem().is(Items.DIAMOND)).stream().mapToInt(e->e.getItem().getCount()).sum();
        h.assertTrue(work.state()==JobState.FAILED && offer.getUses()==1 && f.actor.getInventory().countItem(Items.EMERALD)==63 && dropped==3,"One native trade completes, conserves its world overflow, and stops the batch");
        h.assertTrue(work.result().get("trades").getAsInt()==1 && work.result().get("acquired").getAsInt()==0 && work.result().get("overflow").getAsInt()==3 && work.result().getAsJsonArray("acquiredStacks").isEmpty() && f.actor.containerMenu==f.actor.inventoryMenu,"Failure reports actual overflow without inventing carried outputs");
        f.close();pass("merchant_native_overflow_partial_conservation");h.succeed();
    }
    @GameTest(batch="trading",template="arena",timeoutTicks=50)
    public static void changedOfferObstructionAndRawSyntaxCannotSpendItems(GameTestHelper h){
        var f=new Fixture(h,5);var offer=offer(1,0,new ItemStack(Items.BOOK),5);f.villager.getOffers().add(offer);f.actor.getInventory().setItem(0,new ItemStack(Items.EMERALD,8));String stale=f.trade(offer,1);offer.setSpecialPriceDiff(2);
        var changed=f.direct(stale);changed.tick();h.assertTrue(changed.state()==JobState.FAILED && offer.getUses()==0 && f.actor.getInventory().countItem(Items.EMERALD)==8,"A changed native price fails before any payment");
        for(int y=2;y<6;y++)for(int z=3;z<8;z++)h.setBlock(new BlockPos(4,y,z),Blocks.STONE);var hidden=f.direct(f.trade(offer,1));hidden.tick();h.assertTrue(hidden.state()==JobState.FAILED && offer.getUses()==0,"Merchant offers cannot be interacted with through a wall");
        for(String invalid:new String[]{"{\"op\":\"trade\",\"target\":\"bad\",\"offer\":0,\"offerId\":\""+"a".repeat(64)+"\"}","{\"op\":\"trade\",\"target\":\""+f.villager.getUUID()+"\",\"offer\":-1,\"offerId\":\""+"a".repeat(64)+"\"}",f.trade(offer,0)}){
            try{OperationSyntax.validate(json(invalid));throw new AssertionError("Invalid trade syntax accepted");}catch(IllegalArgumentException expected){}
        }
        f.close();pass("merchant_changed_price_obstruction_syntax");h.succeed();
    }
    @GameTest(batch="trading",template="arena",timeoutTicks=50)
    public static void wanderingTraderSupportsSellingCarriedItems(GameTestHelper h){
        var f=new Fixture(h,10);f.villager.discard();var trader=h.spawn(EntityType.WANDERING_TRADER,new BlockPos(5,2,5));trader.setNoAi(true);trader.getOffers().clear();var offer=new MerchantOffer(new ItemStack(Items.PAPER,3),new ItemStack(Items.EMERALD,2),4,1,0);trader.getOffers().add(offer);f.actor.getInventory().setItem(0,new ItemStack(Items.PAPER,6));
        var step=json(f.trade(offer,2));step.addProperty("target",trader.getUUID().toString());var work=f.direct(step.toString());work.tick();work.tick();
        h.assertTrue(work.state()==JobState.SUCCEEDED && offer.getUses()==2 && f.actor.getInventory().countItem(Items.PAPER)==0 && f.actor.getInventory().countItem(Items.EMERALD)==4 && trader.getTradingPlayer()==null,"Native merchant interface supports selling as well as buying, including wandering traders");
        trader.discard();f.close();pass("merchant_wandering_trader_sell_items");h.succeed();
    }
    private static final class Fixture{
        final GameTestHelper h;final AvatarManager m;final UUID owner=UUID.randomUUID();final AvatarEntry entry;final CompanionPlayer actor;final Villager villager;
        Fixture(GameTestHelper h,int x){this.h=h;m=ServerEvents.avatars(h.getLevel().getServer());entry=m.spawn(owner,h.getLevel(),h.absoluteVec(new Vec3(3.5,2,5.5)),name());actor=m.actor(entry.id);villager=h.spawn(EntityType.VILLAGER,new BlockPos(x,2,5));villager.setNoAi(true);villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER).setLevel(2));villager.getOffers().clear();}
        String trade(MerchantOffer offer,int quantity){return "{\"op\":\"trade\",\"target\":\""+villager.getUUID()+"\",\"offer\":0,\"offerId\":\""+MerchantJob.offerId(offer)+"\",\"quantity\":"+quantity+",\"saveAs\":\"loot\"}";}
        MerchantJob direct(String specification){var spec=json(specification);OperationSyntax.validate(spec);return new MerchantJob(UUID.randomUUID(),actor,spec);}
        GameJob submit(String program){return m.jobs().submitProgram(owner,entry.id,entry.authority.session(),UUID.randomUUID(),json(program),"replace");}
        void close(){villager.discard();var current=m.actor(entry.id);if(current!=null)current.getInventory().clearContent();m.destroy(owner,entry.id);}
    }
    private static MerchantOffer offer(int emeralds,int paper,ItemStack output,int stock){return new MerchantOffer(new ItemStack(Items.EMERALD,emeralds),paper==0?ItemStack.EMPTY:new ItemStack(Items.PAPER,paper),output,stock,2,0);}
    private static JsonObject json(String value){return JsonParser.parseString(value).getAsJsonObject();}
    private static String name(){return "QM"+UUID.randomUUID().toString().substring(0,8);}
    private static void check(GameTestHelper h,GameJob job){h.assertTrue(job.state()!=JobState.FAILED && job.state()!=JobState.CANCELLED,"Merchant program failed: "+job.view());}
    private static void pass(String event){Paracraft.LOG.info("QA_EVT {\"event\":\"{}\",\"status\":\"pass\"}",event);}
}

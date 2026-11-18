package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.google.gson.*;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.common.ForgeHooks;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Explicit merchant interaction. Each native result pickup is indivisible and checkpointed once. */
public final class MerchantJob extends GameJob {
    private final JsonObject specification;
    private final UUID target;
    private final int quantity;
    private Merchant merchant;
    private MerchantMenu menu;
    private int trades;
    private long produced,carried;
    private final List<ItemStack> acquired=new ArrayList<>();

    public MerchantJob(UUID id,CompanionPlayer actor,JsonObject specification){
        super(id,specification.get("op").getAsString(),actor);this.specification=specification.deepCopy();
        target=UUID.fromString(specification.get("target").getAsString());quantity=specification.has("quantity")?specification.get("quantity").getAsInt():1;update();
    }
    @Override protected void advance(){
        if(type.equals("trade") && trades>=quantity){succeed(result);return;}
        if(actor.isSpectator() || actor.isUsingItem())throw new IllegalStateException("Avatar cannot open a merchant menu in its current state");
        Entity entity=actor.serverLevel().getEntity(target);
        if(entity==null || entity==actor || !entity.isAlive() || entity.isRemoved() || !(entity instanceof Merchant found))throw new IllegalStateException("Merchant is unavailable in this dimension");
        phase="approaching_merchant";if(!navigate(entity.position(),2.0))return;
        merchant=found;var point=entity.getBoundingBox().clip(actor.getEyePosition(),entity.getBoundingBox().getCenter()).orElse(entity.getBoundingBox().getCenter());
        if(!actor.canReach(point,0) || actor.level().clip(new ClipContext(actor.getEyePosition(),point,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,actor)).getType()!=HitResult.Type.MISS)
            throw new IllegalStateException("Merchant interaction is obstructed or out of reach");
        actor.closeContainer();actor.lookAt(EntityAnchorArgument.Anchor.EYES,point);actor.resetLastActionTime();
        // Spawn eggs have a different native entity interaction; do not turn an inspection into breeding/spawning.
        var hand=actor.getMainHandItem().getItem() instanceof SpawnEggItem?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND;
        if(actor.getItemInHand(hand).getItem() instanceof SpawnEggItem)throw new IllegalStateException("Free a hand of spawn eggs before opening a merchant");
        boolean sneaking=actor.isShiftKeyDown();InteractionResult outcome;
        try{
            actor.setShiftKeyDown(false);var relative=point.subtract(entity.position());var hook=ForgeHooks.onInteractEntityAt(actor,entity,relative,hand);
            outcome=hook==null?entity.interactAt(actor,relative,hand):hook;
            if(hook==null && outcome==InteractionResult.PASS)outcome=actor.interactOn(entity,hand);
        }finally{actor.setShiftKeyDown(sneaking);}
        if(!outcome.consumesAction())throw new IllegalStateException("Native merchant interaction was refused: "+outcome);
        if(!(actor.containerMenu instanceof MerchantMenu actual) || merchant.getTradingPlayer()!=actor){
            if(type.equals("inspect_trades") && merchant.getOffers().isEmpty()){observe(entity);succeed(result);return;}
            throw new IllegalStateException("Merchant did not open a native trading menu (busy, unavailable, or unsupported menu)");
        }
        menu=actual;
        if(!menu.stillValid(actor) || menu.getOffers()!=merchant.getOffers())throw new IllegalStateException("Merchant menu no longer belongs to this trade");
        if(type.equals("inspect_trades")){phase="inspecting_trades";observe(entity);succeed(result);return;}
        phase="trading";int index=specification.get("offer").getAsInt();
        if(index>=menu.getOffers().size())throw new IllegalStateException("Selected merchant offer no longer exists; inspect again");
        var offer=menu.getOffers().get(index);
        if(!offerId(offer).equals(specification.get("offerId").getAsString()))throw new IllegalStateException("Merchant prices or output changed; inspect the offers again before trading");
        if(offer.isOutOfStock())throw new IllegalStateException("Selected merchant offer is out of stock");
        var plan=payments(actor,offer);if(plan==null)throw new IllegalStateException("Carried inventory lacks the native trade's required items or NBT");
        menu.setSelectionHint(index);menu.getSlot(0).set(remove(plan[0],offer.getCostA().getCount()));menu.getSlot(1).set(remove(plan[1],offer.getCostB().getCount()));
        var output=menu.getSlot(2);var offered=output.getItem().copy();
        if(offered.isEmpty() || !output.mayPickup(actor) || !(output.container instanceof MerchantContainer container) || container.getActiveOffer()!=offer)
            throw new IllegalStateException("Native merchant menu did not offer the selected trade output");
        var before=actor.getInventory().items.stream().map(ItemStack::copy).toList();int uses=offer.getUses();
        // A normal pickup consumes through MerchantResultSlot, firing native stats, XP and Forge trade hooks.
        // Closing returns its real cursor stack to inventory or drops native overflow; no output is synthesized.
        menu.clicked(2,0,ClickType.PICKUP,actor);
        if(menu.getCarried().isEmpty() && offer.getUses()==uses && actor.containerMenu==menu)throw new IllegalStateException("Native merchant result pickup was refused");
        var taken=menu.getCarried().isEmpty()?offered:menu.getCarried().copy();trades++;produced+=taken.getCount();
        closeMenu();long accepted=rememberActualOutputs(before,taken);carried+=accepted;update();
        result.addProperty("offer",index);result.addProperty("offerId",specification.get("offerId").getAsString());result.addProperty("usesAfter",offer.getUses());
        if(accepted<taken.getCount())throw new IllegalStateException("Trade completed but output did not fully fit; native overflow was dropped at the avatar");
        if(trades>=quantity)succeed(result);
    }
    private void observe(Entity entity){
        var offers=new JsonArray();for(int i=0;i<merchant.getOffers().size();i++){
            var offer=merchant.getOffers().get(i);var value=new JsonObject();value.addProperty("index",i);value.addProperty("offerId",offerId(offer));
            value.add("costA",stack(offer.getCostA()));value.add("costB",stack(offer.getCostB()));value.add("result",stack(offer.getResult()));
            value.addProperty("uses",offer.getUses());value.addProperty("maxUses",offer.getMaxUses());value.addProperty("remaining",Math.max(0,offer.getMaxUses()-offer.getUses()));
            value.addProperty("outOfStock",offer.isOutOfStock());value.addProperty("villagerXp",offer.getXp());value.addProperty("canAfford",payments(actor,offer)!=null);offers.add(value);
        }
        result.add("offers",offers);result.addProperty("observedAt",actor.level().getGameTime());result.addProperty("dimension",actor.serverLevel().dimension().location().toString());result.add("position",JobJson.vector(entity.position()));result.addProperty("merchantXp",merchant.getVillagerXp());
    }
    public static String offerId(MerchantOffer offer){
        String value=offer.getCostA().save(new CompoundTag())+"|"+offer.getCostB().save(new CompoundTag())+"|"+offer.getResult().save(new CompoundTag());
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static JsonObject stack(ItemStack item){var value=new JsonObject();value.addProperty("empty",item.isEmpty());if(!item.isEmpty()){value.addProperty("item",BuiltInRegistries.ITEM.getKey(item.getItem()).toString());value.addProperty("count",item.getCount());value.addProperty("snbt",item.save(new CompoundTag()).toString());}return value;}
    private static List<Integer> sourceSlots(CompanionPlayer actor){var slots=new ArrayList<Integer>();for(int i=0;i<actor.getInventory().items.size();i++)slots.add(i);slots.add(40);return slots;}
    private static ItemStack[] payments(CompanionPlayer actor,MerchantOffer offer){
        var groups=new ArrayList<ItemStack>();for(int slot:sourceSlots(actor)){
            var item=actor.getInventory().getItem(slot);if(item.isEmpty())continue;var group=groups.stream().filter(old->ItemStack.isSameItemSameTags(old,item)).findFirst().orElse(null);
            if(group==null)groups.add(item.copy());else group.grow(item.getCount());
        }
        if(offer.getCostA().isEmpty() || offer.getCostB().isEmpty())groups.add(ItemStack.EMPTY);
        int a=offer.getCostA().getCount(),b=offer.getCostB().getCount();
        for(var first:groups)for(var second:groups){
            if(first.getCount()<a || second.getCount()<b || first==second && first.getCount()<a+b)continue;
            var x=a==0?ItemStack.EMPTY:first.copyWithCount(a);var y=b==0?ItemStack.EMPTY:second.copyWithCount(b);
            if(offer.satisfiedBy(x,y))return new ItemStack[]{x,y};
        }
        return null;
    }
    private ItemStack remove(ItemStack matching,int count){
        if(count==0)return ItemStack.EMPTY;ItemStack taken=ItemStack.EMPTY;
        for(int slot:sourceSlots(actor)){
            var stack=actor.getInventory().getItem(slot);if(!ItemStack.isSameItemSameTags(stack,matching))continue;
            var part=actor.getInventory().removeItem(slot,Math.min(count-taken.getCount(),stack.getCount()));
            if(taken.isEmpty())taken=part;else taken.grow(part.getCount());if(taken.getCount()==count)return taken;
        }
        throw new IllegalStateException("Carried trade payment changed before native menu insertion");
    }
    private long rememberActualOutputs(List<ItemStack> before,ItemStack expected){
        var groups=new ArrayList<ItemStack>();for(var stack:actor.getInventory().items)if(stack.is(expected.getItem())){
            var group=groups.stream().filter(old->ItemStack.isSameItemSameTags(old,stack)).findFirst().orElse(null);if(group==null)groups.add(stack.copy());else group.grow(stack.getCount());
        }
        long accepted=0;for(var stack:groups){
            long prior=before.stream().filter(old->ItemStack.isSameItemSameTags(old,stack)).mapToLong(ItemStack::getCount).sum();long amount=Math.min(expected.getCount()-accepted,Math.max(0,stack.getCount()-prior));accepted+=amount;
            while(amount>0){int count=(int)Math.min(amount,stack.getMaxStackSize());acquired.add(stack.copyWithCount(count));amount-=count;}
        }return accepted;
    }
    private void update(){result.addProperty("target",target.toString());result.addProperty("requested",quantity);result.addProperty("trades",trades);result.addProperty("produced",produced);result.addProperty("acquired",carried);result.addProperty("overflow",produced-carried);result.add("acquiredStacks",JobJson.stacks(acquired));}
    private void closeMenu(){if(menu!=null && actor.containerMenu==menu)actor.closeContainer();menu=null;if(merchant!=null && merchant.getTradingPlayer()==actor && actor.containerMenu==actor.inventoryMenu)merchant.setTradingPlayer(null);}
    @Override protected void onStop(){closeMenu();}
    @Override protected JsonObject writeData(){var data=new JsonObject();data.add("specification",specification.deepCopy());data.addProperty("trades",trades);data.addProperty("produced",produced);data.addProperty("carried",carried);data.add("acquired",JobJson.stacks(acquired));return data;}
    @Override protected void readData(JsonObject data){trades=data.get("trades").getAsInt();produced=data.get("produced").getAsLong();carried=data.get("carried").getAsLong();acquired.clear();acquired.addAll(JobJson.stacks(data.getAsJsonArray("acquired")));update();}
}

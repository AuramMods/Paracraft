package com.aurammods.paracraft.forge.avatar;

import com.aurammods.paracraft.forge.execution.CarriedItems;
import com.aurammods.paracraft.forge.execution.JobJson;
import com.aurammods.paracraft.forge.execution.PlayerNavigator;
import com.aurammods.paracraft.forge.inventory.InventoryAccess;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** Quiet server-only activity. No tasks, inference, wake events, invented items, or movement teleports. */
final class AmbientIdle implements AutoCloseable {
    static final double RADIUS = 16;
    private final AvatarManager avatars;
    private final AvatarEntry entry;
    private final CompanionPlayer actor;
    private final JsonObject survival, state;
    private Vec3 center, destination, ownerPrevious;
    private PlayerNavigator navigator;
    private BlockPos interaction;
    private boolean restocking, ownsSleep, sawWork;
    private Scan scan;
    private long nextWalk, nextLook, lookUntil, nextScan;
    private float yaw, pitch;
    private boolean lookingAtOwner;
    private int lookSequence, movementTicks;
    private final Map<BlockPos,Long> unavailable = new HashMap<>();

    AmbientIdle(AvatarManager avatars, AvatarEntry entry, CompanionPlayer actor, JsonObject survival) {
        this.avatars=avatars;this.entry=entry;this.actor=actor;this.survival=survival;
        if(!survival.has("ambient"))survival.add("ambient",new JsonObject());state=survival.getAsJsonObject("ambient");
        if(!state.has("center") || contextChanged())reanchor();else center=JobJson.vector(state.get("center"));
        nextWalk=now()+100+random(100);nextLook=now()+30;nextScan=now()+10;
    }
    private long now(){return actor.level().getGameTime();}
    private int random(int bound){return actor.getRandom().nextInt(bound);}
    private long life(){return entry.control.has("lifeRevision")?entry.control.get("lifeRevision").getAsLong():0;}
    private boolean contextChanged(){return !state.has("dimension") || !state.get("dimension").getAsString().equals(actor.serverLevel().dimension().location().toString())
            || !state.has("teleportSerial") || state.get("teleportSerial").getAsLong()!=actor.teleportSerial()
            || !state.has("lifeRevision") || state.get("lifeRevision").getAsLong()!=life();}
    void reanchor(){
        suspend();center=actor.position();state.add("center",JobJson.vector(center));state.addProperty("radius",RADIUS);
        state.addProperty("dimension",actor.serverLevel().dimension().location().toString());state.addProperty("teleportSerial",actor.teleportSerial());state.addProperty("lifeRevision",life());
        nextWalk=now()+100+random(100);nextScan=now()+10;unavailable.clear();avatars.dirty();
    }
    void observeWork(boolean working){
        if(contextChanged())reanchor();
        if(working){suspend();sawWork=true;}
        else if(sawWork){reanchor();sawWork=false;}
    }
    void configured(){reanchor();if(survival.getAsJsonObject("behavior").get("ambientIdle").getAsBoolean())phase("resting");else state.addProperty("state","disabled");}
    private boolean inside(Vec3 point){return point.distanceToSqr(center)<=15.5*15.5;}
    private boolean needsFood(){return actor.getFoodData().getFoodLevel()<=14 || actor.getHealth()<actor.getMaxHealth() && actor.getFoodData().getFoodLevel()<20;}
    private boolean wantsFood(){return !actor.getAbilities().instabuild && !CarriedItems.hasFood(actor);}
    private void phase(String phase){state.addProperty("state",phase);survival.addProperty("mode","idle_"+phase);}
    private void count(String key,int amount){state.addProperty(key,(state.has(key)?state.get(key).getAsLong():0)+amount);}

    void tick(){
        if(entry.paused || !survival.getAsJsonObject("behavior").get("ambientIdle").getAsBoolean()
                || survival.getAsJsonObject("behavior").getAsJsonObject("idle").get("enabled").getAsBoolean()
                || avatars.jobs().hasUnfinished(entry.id) || actor.isSpectator()) {suspend();return;}
        if(avatars.endpoint().activity(entry.id).thinking()){
            holdForThinking();if(!actor.isSleeping())look();phase("thinking");return;
        }
        if(actor.isSleeping()){
            if(!ownsSleep)return;
            if(actor.serverLevel().isDay() || needsFood()){suspend();nextScan=now()+100;}
            else {phase("sleeping");return;}
        }
        ownsSleep=false;
        if(navigator!=null){move();return;}
        look();
        if(scan!=null){scan.tick();if(!scan.done())return;var complete=scan;scan=null;
            if(wantsFood() && complete.food!=null){begin(complete.food,true);return;}
            if(actor.serverLevel().isNight() && complete.bed!=null){begin(complete.bed,false);return;}
        }
        if(now()>=nextScan && (wantsFood() || actor.serverLevel().isNight())) {
            nextScan=now()+200+random(100);unavailable.values().removeIf(expiry->expiry<=now());scan=new Scan();return;
        }
        if(now()<nextWalk){if(now()>=lookUntil)phase("resting");return;}
        nextWalk=now()+100+random(160);
        // Short walks from the current position, inside a fixed center; wandering never moves the center.
        for(int attempt=0;attempt<12;attempt++){
            double angle=actor.getRandom().nextDouble()*Math.PI*2, distance=2+actor.getRandom().nextDouble()*4;
            BlockPos horizontal=BlockPos.containing(actor.position().add(Math.cos(angle)*distance,0,Math.sin(angle)*distance));
            for(int dy=2;dy>=-2;dy--){BlockPos feet=horizontal.offset(0,dy,0);Vec3 point=Vec3.atBottomCenterOf(feet);
                if(!inside(point) || point.distanceToSqr(actor.position())<2 || !actor.serverLevel().hasChunkAt(feet))continue;
                var support=actor.serverLevel().getBlockState(feet.below());
                if(!support.isCollisionShapeFullBlock(actor.serverLevel(),feet.below()) || !actor.serverLevel().getFluidState(feet).isEmpty()
                        || !actor.serverLevel().noCollision(actor,actor.getBoundingBox().move(point.subtract(actor.position()))))continue;
                destination=point;navigator=new PlayerNavigator(actor,center,RADIUS);interaction=null;movementTicks=0;phase("wandering");state.add("destination",JobJson.vector(point));return;
            }
        }
        phase("resting");
    }
    private void begin(BlockPos target,boolean food){
        if(!inside(Vec3.atCenterOf(target)))return;
        interaction=target.immutable();restocking=food;destination=Vec3.atBottomCenterOf(target);movementTicks=0;
        navigator=new PlayerNavigator(actor,center,RADIUS);state.add("target",JobJson.position(target));phase(food?"restocking":"bed_approach");
    }
    private void move(){
        try{
            if(++movementTicks>400)throw new IllegalStateException("Idle route timed out");
            if(!navigator.tick(destination,interaction==null?0.7:2.5))return;
            navigator.close();navigator=null;state.remove("destination");
            if(interaction==null){count("walks",1);phase("resting");nextWalk=now()+100+random(160);return;}
            BlockPos target=interaction;interaction=null;
            if(!inside(Vec3.atCenterOf(target)))throw new IllegalStateException("Idle interaction left the resting area");
            if(restocking){
                InventoryAccess.open(actor,target);
                int acquired=InventoryAccess.takeMatching(actor,target,stack->CarriedItems.suitableFood(actor,stack),4,stack->count("foodAcquired",stack.getCount()));
                actor.closeContainer();if(acquired==0)throw new IllegalStateException("Nearby inventory supplied no suitable food through its permitted faces");
                state.remove("target");phase("resting");nextWalk=now()+120;avatars.dirty();
            }else{
                var block=actor.serverLevel().getBlockState(target);
                if(!actor.serverLevel().isNight() || !(block.getBlock() instanceof BedBlock) || block.getValue(BedBlock.OCCUPIED) || !BedBlock.canSetSpawn(actor.serverLevel()))
                    throw new IllegalStateException("Nearby bed is no longer available for sleeping");
                actor.clearSleepProblem();InventoryAccess.open(actor,target);ownsSleep=actor.isSleeping();
                if(!ownsSleep)throw new IllegalStateException("Native sleep refused: "+actor.sleepProblem());
                count("sleeps",1);phase("sleeping");avatars.dirty();
            }
        }catch(IllegalArgumentException | IllegalStateException failed){
            if(interaction!=null)unavailable.put(interaction,now()+600);
            if(state.has("target"))unavailable.put(JobJson.position(state.get("target")),now()+600);
            state.addProperty("lastError",failed.getMessage());suspend();nextWalk=now()+100;nextScan=now()+100;phase("resting");
        }
    }
    private void look(){
        var owner=actor.server.getPlayerList().getPlayer(entry.authority.owner());
        boolean moving=owner!=null && owner.serverLevel()==actor.serverLevel() && (owner.getDeltaMovement().horizontalDistance()>0.02
                || ownerPrevious!=null && owner.position().distanceToSqr(ownerPrevious)>0.001);
        ownerPrevious=owner==null?null:owner.position();
        if(now()>=nextLook){
            lookingAtOwner=lookSequence++%3==0 && moving && actor.distanceToSqr(owner)<=RADIUS*RADIUS && actor.hasLineOfSight(owner);
            if(lookingAtOwner)count("ownerGlances",1);else{yaw=actor.getRandom().nextFloat()*360-180;pitch=actor.getRandom().nextFloat()*35-15;count("randomLooks",1);}
            lookUntil=now()+20+random(20);nextLook=lookUntil+60+random(100);
        }
        if(now()>=lookUntil)return;
        if(lookingAtOwner && owner!=null && owner.serverLevel()==actor.serverLevel()){
            Vec3 delta=owner.getEyePosition().subtract(actor.getEyePosition());yaw=(float)(Math.toDegrees(Math.atan2(delta.z,delta.x))-90);
            pitch=(float)-Math.toDegrees(Math.atan2(delta.y,delta.horizontalDistance()));phase("owner_glance");
        }else phase("looking");
        float facing=Mth.approachDegrees(actor.getYRot(),yaw,8);actor.setYRot(facing);actor.setYHeadRot(facing);actor.setXRot(Mth.approachDegrees(actor.getXRot(),pitch,5));
    }
    private void stopRoute(){
        if(navigator!=null){navigator.close();navigator=null;}
        interaction=null;scan=null;state.remove("destination");state.remove("target");
    }
    void holdForThinking(){
        stopRoute();
        // Preserve a bed already in use and never stop navigation owned by work or a reflex.
        if(survival.has("mode") && state.has("state") && survival.get("mode").getAsString().equals("idle_"+state.get("state").getAsString()))phase("thinking");
    }
    void suspend(){
        stopRoute();
        if(ownsSleep && actor.isSleeping())actor.stopSleepInBed(true,true);
        ownsSleep=false;
        if(survival.has("mode") && state.has("state") && survival.get("mode").getAsString().equals("idle_"+state.get("state").getAsString())){
            survival.addProperty("mode","idle");state.addProperty("state","paused");
        }
    }
    @Override public void close(){suspend();}

    /** Loaded block entities only, with bounded block/slot inspection and no terrain generation. */
    private final class Scan {
        final ArrayDeque<net.minecraft.world.level.ChunkPos> chunks=new ArrayDeque<>();
        final ArrayDeque<BlockPos> blocks=new ArrayDeque<>();
        BlockPos bed,food,current;int view,slot;
        Scan(){BlockPos origin=BlockPos.containing(center);for(int x=(origin.getX()-16)>>4;x<=(origin.getX()+16)>>4;x++)for(int z=(origin.getZ()-16)>>4;z<=(origin.getZ()+16)>>4;z++)chunks.add(new net.minecraft.world.level.ChunkPos(x,z));}
        boolean done(){return chunks.isEmpty() && blocks.isEmpty() && current==null;}
        void tick(){
            if(current!=null){inspectFood();return;}
            if(blocks.isEmpty() && !chunks.isEmpty()){
                var next=chunks.remove();var chunk=actor.serverLevel().getChunkSource().getChunkNow(next.x,next.z);
                if(chunk!=null)chunk.getBlockEntities().keySet().stream().filter(pos->inside(Vec3.atCenterOf(pos))).sorted(java.util.Comparator.comparingDouble(pos->actor.distanceToSqr(Vec3.atCenterOf(pos)))).forEach(pos->blocks.add(pos.immutable()));
            }
            for(int budget=0;budget<8 && !blocks.isEmpty();budget++){
                BlockPos pos=blocks.remove();if(unavailable.containsKey(pos))continue;var block=actor.serverLevel().getBlockState(pos);
                if(actor.serverLevel().isNight() && block.getBlock() instanceof BedBlock && block.getValue(BedBlock.PART)==BedPart.HEAD && !block.getValue(BedBlock.OCCUPIED)
                        && BedBlock.canSetSpawn(actor.serverLevel()) && Vec3.atCenterOf(pos).distanceToSqr(center)<=14*14
                        && inside(Vec3.atCenterOf(pos.relative(block.getValue(BedBlock.FACING).getOpposite())))
                        && (bed==null || distance(pos)<distance(bed)))bed=pos;
                if(wantsFood() && (food==null || distance(pos)<distance(food)) && InventoryAccess.resolve(actor.serverLevel(),pos)!=null){current=pos;view=slot=0;inspectFood();return;}
            }
        }
        private double distance(BlockPos pos){return actor.distanceToSqr(Vec3.atCenterOf(pos));}
        private void inspectFood(){
            var target=InventoryAccess.resolve(actor.serverLevel(),current);if(target==null){current=null;return;}
            for(int budget=0;budget<64 && view<target.views().size();budget++){
                var handler=target.views().get(view).handler();if(slot>=handler.getSlots()){view++;slot=0;continue;}
                if(CarriedItems.suitableFood(actor,handler.getStackInSlot(slot++))){food=current;current=null;return;}
            }
            if(view>=target.views().size())current=null;
        }
    }
}

package com.aurammods.paracraft.qa;

import com.aurammods.paracraft.Paracraft;
import com.aurammods.paracraft.core.JobState;
import com.aurammods.paracraft.core.agent.AgentActivity;
import com.aurammods.paracraft.forge.ServerEvents;
import com.aurammods.paracraft.forge.avatar.*;
import com.aurammods.paracraft.forge.execution.JobJson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(Paracraft.ID)
@PrefixGameTestTemplate(false)
public final class ThinkingIdleGameTests {
    @GameTest(batch="thinking_idle",template="arena",timeoutTicks=1000)
    public static void inferenceStopsAnActualWanderKeepsLookingAndResumesAfterward(GameTestHelper h) {
        var s=new Session(h);Vec3 start=s.actor.position();int[] stage={0},held={0};Vec3[] stopped={null};long[] looks={0};float[] heading={0};boolean[] turned={false};
        h.onEachTick(()->{
            s.tick();var ambient=s.ambient();
            if(stage[0]==0){
                if(!s.m.survivalMode(s.entry.id).equals("idle_wandering") || s.actor.position().distanceTo(start)<0.2)return;
                var center=ambient.get("center").deepCopy();stopped[0]=s.actor.position();looks[0]=number(ambient,"randomLooks");heading[0]=s.actor.getYHeadRot();
                s.report(new AgentActivity(AgentActivity.Phase.THINKING,2));stage[0]=1;
                h.assertTrue(!s.ambient().has("destination"),"Receiving Thinking immediately cancels the idle path");
                h.assertTrue(s.ambient().get("center").equals(center),"Inference preserves the resting center");
            }
            if(stage[0]==1 || stage[0]==2){
                h.assertTrue(s.actor.position().distanceTo(stopped[0])<0.05 && !s.actor.isSprinting(),"Thinking/planning remains physically stationary");
                turned[0]|=Math.abs(net.minecraft.util.Mth.wrapDegrees(s.actor.getYHeadRot()-heading[0]))>0.1;
                h.assertTrue(s.m.statuses().label(s.entry.id).equals((stage[0]==1?"Thinking":"Planning")+" · 2 tasks"),"Inference label overrides ambient status");
                if(++held[0]<160)return;
                if(stage[0]==1){s.report(new AgentActivity(AgentActivity.Phase.THINKING,2,true));stage[0]=2;held[0]=0;return;}
                h.assertTrue(number(s.ambient(),"randomLooks")>looks[0] && turned[0],"Passive looks continue and actually turn the head");
                s.report(new AgentActivity(AgentActivity.Phase.IDLE,0));stage[0]=3;
            }
            if(stage[0]==3 && s.actor.position().distanceTo(stopped[0])>1){
                h.assertTrue(s.m.behavior(s.owner.id,s.entry.id).getAsJsonObject("behavior").get("ambientIdle").getAsBoolean(),"Temporary hold never disables ambient behavior");
                s.close();pass("thinking_idle_stationary_looking_status_resume");h.succeed();
            }
        });
    }

    @GameTest(batch="thinking_idle",template="arena",timeoutTicks=650)
    public static void instructedTravelAndConfiguredPatrolStillMoveWhilePlanning(GameTestHelper h) {
        var s=new Session(h);s.report(new AgentActivity(AgentActivity.Phase.THINKING,1,true));Vec3 start=s.actor.position();
        var work=s.m.jobs().submitProgram(s.owner.id,s.entry.id,s.entry.authority.session(),UUID.randomUUID(),
                json("{\"steps\":[{\"op\":\"travel\",\"destination\":"+JobJson.vector(h.absoluteVec(new Vec3(60.5,2,48.5)))+"}]}"),"replace");
        Vec3[] patrolStart={null};
        h.onEachTick(()->{
            s.tick();h.assertTrue(work.state()!=JobState.FAILED && work.state()!=JobState.CANCELLED,"Inference leaves explicit travel running: "+work.view());
            h.assertTrue(s.m.statuses().label(s.entry.id).equals("Planning · 1 task"),"Planning stays visible while instructed movement runs");
            if(work.state()!=JobState.SUCCEEDED)return;
            if(patrolStart[0]==null){
                h.assertTrue(s.actor.position().distanceTo(start)>8,"Explicit travel actually moved the body");patrolStart[0]=s.actor.position();
                var patch=json("{\"idle\":{\"enabled\":true,\"waitTicks\":2,\"waypoints\":[]}}");
                patch.getAsJsonObject("idle").getAsJsonArray("waypoints").add(JobJson.vector(h.absoluteVec(new Vec3(52.5,2,48.5))));
                s.m.configureBehavior(s.owner.id,s.entry.id,patch);return;
            }
            if(s.actor.position().distanceTo(patrolStart[0])<3)return;
            s.close();pass("thinking_explicit_travel_and_patrol_continue");h.succeed();
        });
    }

    @GameTest(batch="thinking_idle",template="arena",timeoutTicks=500)
    public static void automaticInitialBedApproachStopsUntilThinkingEnds(GameTestHelper h) {
        var s=new Session(h);BlockPos head=new BlockPos(55,2,48);var bed=Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.EAST);
        h.setBlock(head.relative(Direction.WEST),bed.setValue(BedBlock.PART,BedPart.FOOT));h.setBlock(head,bed.setValue(BedBlock.PART,BedPart.HEAD));
        s.entry.control.getAsJsonObject("survival").getAsJsonObject("initialBed").addProperty("state","searching");
        int[] stage={0},held={0};Vec3[] stopped={null};
        h.onEachTick(()->{
            s.tick();
            if(stage[0]==0){
                if(!s.m.survivalMode(s.entry.id).equals("establishing_spawn"))return;
                s.report(new AgentActivity(AgentActivity.Phase.THINKING,0));stopped[0]=s.actor.position();stage[0]=1;
            }
            if(stage[0]==1){
                h.assertTrue(s.actor.position().distanceTo(stopped[0])<0.05 && s.actor.getRespawnPosition()==null,"Automatic bed navigation pauses during inference");
                if(++held[0]<100)return;s.report(new AgentActivity(AgentActivity.Phase.IDLE,0));stage[0]=2;
            }
            if(stage[0]==2 && h.absolutePos(head).equals(s.actor.getRespawnPosition())){
                s.close();pass("thinking_automatic_bed_approach_pause_resume");h.succeed();
            }
        });
    }

    @GameTest(batch="thinking_idle",template="arena",timeoutTicks=350)
    public static void fleeingStillMovesWhileThinking(GameTestHelper h) { survivalReaction(h,false); }

    @GameTest(batch="thinking_idle",template="arena",timeoutTicks=350)
    public static void fightingStillMovesWhileThinking(GameTestHelper h) { survivalReaction(h,true); }

    private static void survivalReaction(GameTestHelper h,boolean defend){
        var s=new Session(h);s.report(new AgentActivity(AgentActivity.Phase.THINKING,0,true));Vec3 start=s.actor.position();
        var cow=EntityType.COW.create(h.getLevel());cow.setNoAi(true);cow.moveTo(h.absoluteVec(new Vec3(56.5,2,48.5)));h.getLevel().addFreshEntity(cow);
        s.actor.getInventory().setItem(0,new ItemStack(Items.DIAMOND_SWORD));cow.setHealth(4);boolean[] reacted={false};
        h.runAfterDelay(70,()->h.assertTrue(s.actor.hurt(defend?s.actor.damageSources().mobAttack(cow):s.actor.damageSources().playerAttack(s.m.actor(s.owner.id)),2),"Native attack damages the thinking avatar"));
        h.onEachTick(()->{
            s.tick();reacted[0]|=s.m.survivalMode(s.entry.id).equals(defend?"defending":"fleeing");
            h.assertTrue(s.m.statuses().label(s.entry.id).equals("Planning"),"Inference remains visible during survival movement");
            if(!reacted[0] || s.actor.position().distanceTo(start)<3 || defend && cow.isAlive())return;
            h.assertTrue(defend || s.m.actor(s.owner.id).getHealth()==20,"Fleeing does not retaliate against the player");
            cow.discard();s.close();pass(defend?"thinking_native_defense_continues":"thinking_native_flee_continues");h.succeed();
        });
    }

    private static final class Session {
        final GameTestHelper h;final AvatarManager m;final AvatarEntry owner,entry;final CompanionPlayer actor;
        final UUID client=UUID.randomUUID(),root=UUID.randomUUID();long ticks,sequence;AgentActivity activity=new AgentActivity(AgentActivity.Phase.IDLE,0);
        Session(GameTestHelper h){
            this.h=h;m=ServerEvents.avatars(h.getLevel().getServer());owner=spawn(root,45.5);entry=spawn(owner.id,48.5);actor=m.actor(entry.id);
            actor.getTags().add("qa_ambient");actor.getInventory().setItem(1,new ItemStack(Items.BREAD,8));report(activity);
        }
        AvatarEntry spawn(UUID owner,double x){
            var e=m.spawn(owner,h.getLevel(),h.absoluteVec(new Vec3(x,2,48.5)),"QT"+UUID.randomUUID().toString().substring(0,8));
            e.control.getAsJsonObject("survival").getAsJsonObject("initialBed").addProperty("state","already_set");return e;
        }
        void tick(){if(++ticks%60==0)report(activity);}
        JsonObject ambient(){return m.behavior(owner.id,entry.id).getAsJsonObject("ambient");}
        void report(AgentActivity next){
            activity=next;var poll=new JsonObject();poll.addProperty("protocol",1);poll.addProperty("kind","poll");poll.addProperty("client",client.toString());
            poll.addProperty("requestId",UUID.randomUUID().toString());poll.addProperty("sequence",++sequence);var list=new JsonArray();
            var state=next.json();state.addProperty("avatar",entry.id.toString());list.add(state);poll.add("activities",list);
            m.endpoint().receive(m.actor(owner.id),poll,reply->h.assertTrue(reply.get("ok").getAsBoolean(),"Owner reports actual activity: "+reply));
        }
        void close(){actor.getInventory().clearContent();m.destroy(owner.id,entry.id);m.destroy(root,owner.id);m.endpoint().disconnect(owner.id);}
    }
    private static long number(JsonObject value,String key){return value.has(key)?value.get(key).getAsLong():0;}
    private static JsonObject json(String text){return JsonParser.parseString(text).getAsJsonObject();}
    private static void pass(String event){Paracraft.LOG.info("QA_EVT {\"event\":\"{}\",\"status\":\"pass\"}",event);}
}

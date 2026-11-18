package com.aurammods.paracraft.qa;

import com.aurammods.paracraft.Paracraft;
import com.aurammods.paracraft.core.JobState;
import com.aurammods.paracraft.forge.ServerEvents;
import com.aurammods.paracraft.forge.avatar.AvatarEntry;
import com.aurammods.paracraft.forge.avatar.AvatarManager;
import com.aurammods.paracraft.forge.execution.GameJob;
import com.aurammods.paracraft.forge.execution.JobJson;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(Paracraft.ID)
@PrefixGameTestTemplate(false)
public final class NavigationGameTests {
    @net.minecraft.gametest.framework.BeforeBatch(batch="terrain_recovery")
    public static void enableTerrainRecovery(net.minecraft.server.level.ServerLevel level) { Paracraft.NAVIGATION_TERRAIN_CHANGES.set(true); }
    @net.minecraft.gametest.framework.AfterBatch(batch="terrain_recovery")
    public static void restorePreservingRoutes(net.minecraft.server.level.ServerLevel level) { Paracraft.NAVIGATION_TERRAIN_CHANGES.set(false); }

    @GameTest(batch="navigation",template="arena",timeoutTicks=1000)
    public static void fenceMazeUsesLongDetoursAndSprintingWithoutAlteringBuilds(GameTestHelper h) {
        // Four alternating fence walls require walking the maze, rather than walking straight east.
        for(int x=10;x<=46;x++)for(int z=10;z<=46;z++)if(x==10 || x==46 || z==10 || z==46)
            for(int y=2;y<=4;y++)h.setBlock(new BlockPos(x,y,z),Blocks.STONE);
        var fences=new java.util.ArrayList<BlockPos>();
        for(int i=0;i<4;i++)for(int z=11;z<=45;z++)if(i%2==0 ? z<43 : z>13) {
            var pos=new BlockPos(17+i*7,2,z);h.setBlock(pos,Blocks.OAK_FENCE);fences.add(pos);
        }
        var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner,new Vec3(12.5,2,12.5));var actor=m.actor(entry.id);
        actor.getInventory().setItem(0,new ItemStack(Items.IRON_AXE));actor.getInventory().setItem(1,new ItemStack(Items.COBBLESTONE,8));
        var work=travel(h,m,owner,entry,new Vec3(43.5,2,12.5));boolean[] sprint={false};double[] distance={0};Vec3[] previous={actor.position()};
        h.onEachTick(()->{check(h,work);sprint[0]|=actor.isSprinting();double step=actor.position().distanceTo(previous[0]);distance[0]+=step;previous[0]=actor.position();
            h.assertTrue(step<1,"Maze movement stays physical");if(work.state()!=JobState.SUCCEEDED)return;
            h.assertTrue(sprint[0] && !actor.isSprinting() && distance[0]>120,"Avatar sprinted through a real detour and stopped sprinting on arrival");
            for(var pos:fences)h.assertTrue(h.getLevel().getBlockState(h.absolutePos(pos)).is(Blocks.OAK_FENCE),"Maze fences remain intact");
            h.assertTrue(actor.getInventory().getItem(0).getDamageValue()==0 && actor.getInventory().countItem(Items.COBBLESTONE)==8,"Walking consumes neither tools nor building blocks");
            clear(m,owner,entry);pass("navigation_fence_maze_sprint_preserves_build");h.succeed();});
    }
    @GameTest(batch="navigation",template="empty",timeoutTicks=220)
    public static void sealedFenceFailsWithoutBreakingOrPlacingBlocks(GameTestHelper h) {
        corridor(h,false);var fence=new BlockPos(6,2,3);h.setBlock(fence,Blocks.OAK_FENCE);
        var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner,new Vec3(3.5,2,3.5));var actor=m.actor(entry.id);
        actor.getInventory().setItem(0,new ItemStack(Items.IRON_AXE));actor.getInventory().setItem(1,new ItemStack(Items.COBBLESTONE,8));
        var work=travel(h,m,owner,entry,new Vec3(10.5,2,3.5));h.onEachTick(()->{if(work.state()!=JobState.FAILED)return;
            h.assertTrue(work.view().get("error").getAsString().contains("preserves blocks") && h.getLevel().getBlockState(h.absolutePos(fence)).is(Blocks.OAK_FENCE),"No route reports a useful failure while retaining the fence");
            h.assertTrue(actor.getInventory().getItem(0).getDamageValue()==0 && actor.getInventory().countItem(Items.COBBLESTONE)==8 && !actor.isSprinting(),"Failure releases sprinting without consuming supplies");
            clear(m,owner,entry);pass("navigation_no_route_preserves_fence");h.succeed();});
    }
    @GameTest(batch="navigation",template="empty",timeoutTicks=200)
    public static void fenceGateOpensWithoutBreakingTheFence(GameTestHelper h) {
        corridor(h,false);var gate=h.absolutePos(new BlockPos(6,2,3));
        h.getLevel().setBlockAndUpdate(gate,Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(net.minecraft.world.level.block.FenceGateBlock.FACING,net.minecraft.core.Direction.EAST));
        var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner,new Vec3(3.5,2,3.5));var work=travel(h,m,owner,entry,new Vec3(10.5,2,3.5));
        h.onEachTick(()->{check(h,work);if(work.state()!=JobState.SUCCEEDED)return;
            h.assertTrue(h.getLevel().getBlockState(gate).is(Blocks.OAK_FENCE_GATE) && h.getLevel().getBlockState(gate).getValue(net.minecraft.world.level.block.FenceGateBlock.OPEN),"Native gate interaction opened and retained the actual gate");
            clear(m,owner,entry);pass("navigation_native_fence_gate");h.succeed();});
    }
    @GameTest(batch="navigation",template="empty",timeoutTicks=200)
    public static void hungryTravelWalksAndPauseReleasesSprint(GameTestHelper h) {
        corridor(h,false);var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner,new Vec3(2.5,2,3.5));var actor=m.actor(entry.id);
        actor.getFoodData().setFoodLevel(6);var work=travel(h,m,owner,entry,new Vec3(11.5,2,3.5));boolean[] done={false};
        h.onEachTick(()->{if(done[0])return;check(h,work);h.assertTrue(!actor.isSprinting(),"Hunger below native sprint threshold keeps walking");if(work.state()!=JobState.SUCCEEDED)return;
            done[0]=true;actor.getFoodData().setFoodLevel(20);var next=travel(h,m,owner,entry,new Vec3(2.5,2,3.5));
            h.runAfterDelay(3,()->{h.assertTrue(actor.isSprinting(),"Fed travel enables sprinting");next.pause();h.assertTrue(!actor.isSprinting(),"Pausing releases sprinting");clear(m,owner,entry);pass("navigation_hunger_and_sprint_pause");h.succeed();});
        });
    }

    @GameTest(batch="navigation",template="empty",timeoutTicks=200)
    public static void woodenDoorOpensThroughNativeInteraction(GameTestHelper h){
        corridor(h,false);var bottom=h.absolutePos(new BlockPos(6,2,3));var state=Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING,net.minecraft.core.Direction.EAST);
        h.getLevel().setBlockAndUpdate(bottom,state);h.getLevel().setBlockAndUpdate(bottom.above(),state.setValue(net.minecraft.world.level.block.DoorBlock.HALF,net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner,new Vec3(3.5,2,3.5));var work=travel(h,m,owner,entry,new Vec3(10.5,2,3.5));
        h.onEachTick(()->{check(h,work);if(work.state()!=JobState.SUCCEEDED)return;h.assertTrue(h.getLevel().getBlockState(bottom).is(Blocks.OAK_DOOR) && h.getLevel().getBlockState(bottom).getValue(net.minecraft.world.level.block.DoorBlock.OPEN),"Door was natively opened and retained");clear(m,owner,entry);pass("navigation_native_wooden_door");h.succeed();});
    }
    @GameTest(batch="navigation",template="empty",timeoutTicks=200)
    public static void ladderRouteReachesAnExistingUpperPlatform(GameTestHelper h){
        floor(h);for(int y=2;y<=6;y++){h.getLevel().setBlockAndUpdate(h.absolutePos(new BlockPos(4,y,3)),Blocks.STONE.defaultBlockState());h.getLevel().setBlockAndUpdate(h.absolutePos(new BlockPos(3,y,3)),Blocks.LADDER.defaultBlockState().setValue(net.minecraft.world.level.block.LadderBlock.FACING,net.minecraft.core.Direction.WEST));}
        var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner,new Vec3(3.5,2,3.5));var actor=m.actor(entry.id);var work=travel(h,m,owner,entry,new Vec3(4.5,7,3.5));
        h.onEachTick(()->{check(h,work);if(work.state()!=JobState.SUCCEEDED)return;h.assertTrue(actor.onGround() && Math.abs(actor.getY()-h.absoluteVec(new Vec3(4.5,7,3.5)).y)<0.1,"Physical ladder ascent ends on actual upper support");clear(m,owner,entry);pass("navigation_native_ladder_ascent");h.succeed();});
    }
    @GameTest(batch="navigation",template="empty",timeoutTicks=200)
    public static void underwaterTravelUsesPhysicalSwimming(GameTestHelper h){
        floor(h);for(int x=0;x<=13;x++)for(int z=0;z<=7;z++)for(int y=2;y<=6;y++)if(x==0 || x==13 || z==0 || z==7)h.getLevel().setBlockAndUpdate(h.absolutePos(new BlockPos(x,y,z)),Blocks.STONE.defaultBlockState());
        for(int x=1;x<=12;x++)for(int z=1;z<=6;z++)for(int y=2;y<=5;y++)h.getLevel().setBlockAndUpdate(h.absolutePos(new BlockPos(x,y,z)),Blocks.WATER.defaultBlockState());
        var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner,new Vec3(2.5,3,3.5));var actor=m.actor(entry.id);Vec3 start=actor.position();var work=travel(h,m,owner,entry,new Vec3(10.5,3,3.5));
        h.onEachTick(()->{check(h,work);if(work.state()!=JobState.SUCCEEDED)return;h.assertTrue(actor.isInWater() && actor.position().distanceTo(start)>7,"Native physics swam through the actual water volume");clear(m,owner,entry);pass("navigation_native_swimming");h.succeed();});
    }

    @GameTest(batch = "mixed_navigation", template = "empty", timeoutTicks = 420)
    public static void findsALadderFromAcrossTheRoomAndOpensItsTrapdoor(GameTestHelper h) {
        floor(h);
        for (int y = 2; y <= 6; y++) set(h, 7, y, 4, Blocks.STONE.defaultBlockState());
        for (int y = 2; y <= 5; y++) set(h, 6, y, 4, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.WEST));
        for (int x = 7; x <= 11; x++) for (int z = 3; z <= 5; z++) set(h, x, 6, z, Blocks.STONE.defaultBlockState());
        BlockPos hatch = h.absolutePos(new BlockPos(6, 6, 4));
        var closed = Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.FACING, Direction.WEST);
        h.getLevel().setBlockAndUpdate(hatch, closed);
        var manager = manager(h);
        UUID owner = UUID.randomUUID();
        Vec3 ground = new Vec3(2.5, 2, 4.5);
        var entry = spawn(h, manager, owner, ground);
        var actor = manager.actor(entry.id);
        var upper = h.absoluteVec(new Vec3(10.5, 7, 4.5));
        GameJob[] job = {travel(h, manager, owner, entry, new Vec3(10.5, 7, 4.5))};
        boolean[] returning = {false}, descending = {false};
        h.onEachTick(() -> {
            check(h, job[0]);
            if (returning[0] && actor.onClimbable() && actor.getDeltaMovement().y < -0.05) descending[0] = true;
            if (job[0].state() != JobState.SUCCEEDED) return;
            if (!returning[0]) {
                h.assertTrue(actor.onGround() && actor.position().distanceTo(upper) < 0.71
                        && h.getLevel().getBlockState(hatch).getValue(TrapDoorBlock.OPEN),
                        "Ground travel finds the ladder, opens the hatch, and reaches the upper floor");
                h.getLevel().setBlockAndUpdate(hatch, closed);
                returning[0] = true;
                job[0] = travel(h, manager, owner, entry, ground);
                return;
            }
            h.assertTrue(descending[0] && h.getLevel().getBlockState(hatch).getValue(TrapDoorBlock.OPEN) && actor.getHealth() == 20,
                    "The return route opens the hatch and descends the ladder safely");
            clear(manager, owner, entry);
            h.succeed();
        });
    }

    @GameTest(batch = "mixed_navigation", template = "empty", timeoutTicks = 200)
    public static void walksUpAStaircaseToARaisedFloor(GameTestHelper h) {
        floor(h);
        for (int x = 5; x <= 8; x++) {
            int height = x - 3;
            for (int y = 2; y < height; y++) set(h, x, y, 3, Blocks.STONE.defaultBlockState());
            set(h, x, height, 3, Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
        }
        for (int x = 9; x <= 12; x++) set(h, x, 5, 3, Blocks.STONE.defaultBlockState());
        for (int x = 2; x <= 12; x++) for (int y = 2; y <= 7; y++) {
            set(h, x, y, 2, Blocks.BEDROCK.defaultBlockState());
            set(h, x, y, 4, Blocks.BEDROCK.defaultBlockState());
        }
        var manager = manager(h);
        UUID owner = UUID.randomUUID();
        var entry = spawn(h, manager, owner, new Vec3(2.5, 2, 3.5));
        var actor = manager.actor(entry.id);
        var job = travel(h, manager, owner, entry, new Vec3(11.5, 6, 3.5));
        h.onEachTick(() -> {
            check(h, job);
            if (job.state() != JobState.SUCCEEDED) return;
            h.assertTrue(actor.onGround() && actor.position().distanceTo(h.absoluteVec(new Vec3(11.5, 6, 3.5))) < 0.71,
                    "Native stair movement reaches the raised floor");
            for (int x = 5; x <= 8; x++) h.assertTrue(h.getLevel().getBlockState(h.absolutePos(new BlockPos(x, x - 3, 3))).is(Blocks.STONE_STAIRS),
                    "The staircase remains intact");
            clear(manager, owner, entry);
            h.succeed();
        });
    }

    @GameTest(batch = "mixed_navigation", template = "empty", timeoutTicks = 220)
    public static void divesFromShoreAndSwimsAroundASubmergedWall(GameTestHelper h) {
        floor(h);
        for (int x = 3; x <= 13; x++) for (int z = 0; z <= 9; z++) for (int y = 2; y <= 9; y++) {
            if (y == 9 || x == 3 || x == 13 || z == 0 || z == 9 || x == 7 && z <= 6) set(h, x, y, z, Blocks.STONE.defaultBlockState());
            else set(h, x, y, z, Blocks.WATER.defaultBlockState());
        }
        set(h, 3, 7, 3, Blocks.AIR.defaultBlockState());
        set(h, 3, 8, 3, Blocks.AIR.defaultBlockState());
        set(h, 2, 6, 3, Blocks.STONE.defaultBlockState());
        var manager = manager(h);
        UUID owner = UUID.randomUUID();
        var entry = spawn(h, manager, owner, new Vec3(2.5, 7, 3.5));
        var actor = manager.actor(entry.id);
        var job = travel(h, manager, owner, entry, new Vec3(10.5, 3, 3.5));
        double gapZ = h.absoluteVec(new Vec3(0, 0, 7)).z;
        double wallX = h.absoluteVec(new Vec3(7.5, 0, 0)).x;
        boolean[] usedGap = {false};
        double[] maxZ = {actor.getZ()};
        h.onEachTick(() -> {
            check(h, job);
            maxZ[0] = Math.max(maxZ[0], actor.getZ());
            if (Math.abs(actor.getX() - wallX) < 0.4 && actor.getZ() > gapZ && actor.isInWater()) usedGap[0] = true;
            if (job.state() != JobState.SUCCEEDED) return;
            h.assertTrue(usedGap[0] && actor.isUnderWater() && actor.position().distanceTo(h.absoluteVec(new Vec3(10.5, 3, 3.5))) < 0.71,
                    "The route enters from land, detours around the wall, and reaches the submerged destination. Gap used="
                            + usedGap[0] + " maxZ=" + maxZ[0] + " gapZ=" + gapZ + " underwater=" + actor.isUnderWater() + " position=" + actor.position());
            clear(manager, owner, entry);
            h.succeed();
        });
    }

    @GameTest(batch = "mixed_navigation", template = "empty", timeoutTicks = 180)
    public static void creativeAvatarDivesBeforeOpeningAChestWithinSurfaceReach(GameTestHelper h) {
        floor(h);
        for (int x = 2; x <= 8; x++) for (int z = 2; z <= 8; z++) for (int y = 2; y <= 6; y++)
            set(h, x, y, z, x == 2 || x == 8 || z == 2 || z == 8 ? Blocks.STONE.defaultBlockState() : Blocks.WATER.defaultBlockState());
        BlockPos chest = h.absolutePos(new BlockPos(5, 5, 5));
        h.getLevel().setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.WATERLOGGED, true));
        var manager = manager(h);
        UUID owner = UUID.randomUUID();
        var entry = spawn(h, manager, owner, new Vec3(5.5, 7.3, 5.5));
        var actor = manager.actor(entry.id);
        actor.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        var program = JsonParser.parseString("""
                {"steps":[{"op":"open_inventory","position":%s,"saveAs":"chest"}]}
                """.formatted(JobJson.position(chest))).getAsJsonObject();
        var job = manager.jobs().submitProgram(owner, entry.id, entry.authority.session(), UUID.randomUUID(), program, "replace");
        h.onEachTick(() -> {
            check(h, job);
            if (job.state() != JobState.SUCCEEDED) return;
            h.assertTrue(actor.isInWater() && !actor.getAbilities().flying && actor.getY() <= chest.getY() + 1.15,
                    "An underwater interaction gets to the chest's depth before opening it");
            clear(manager, owner, entry);
            h.succeed();
        });
    }

    @GameTest(batch = "mixed_navigation", template = "empty", timeoutTicks = 120)
    public static void flightOpensAClosedDoorInAnEnclosedHall(GameTestHelper h) {
        corridor(h, false);
        var door = Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING, Direction.EAST);
        set(h, 6, 2, 3, door);
        set(h, 6, 3, 3, door.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        var manager = manager(h);
        UUID owner = UUID.randomUUID();
        var entry = spawn(h, manager, owner, new Vec3(3.5, 2, 3.5));
        manager.actor(entry.id).setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        var job = travel(h, manager, owner, entry, new Vec3(10.5, 2, 3.5));
        h.onEachTick(() -> {
            check(h, job);
            if (job.state() != JobState.SUCCEEDED) return;
            h.assertTrue(h.getLevel().getBlockState(h.absolutePos(new BlockPos(6, 2, 3)))
                    .getValue(net.minecraft.world.level.block.DoorBlock.OPEN), "Flight uses the hand-openable barrier in its route");
            clear(manager, owner, entry);
            h.succeed();
        });
    }
    @GameTest(batch="terrain_recovery",template="empty",timeoutTicks=220)
    public static void cancelledBridgePlacementPreservesItemsAndRestoresSneaking(GameTestHelper h){
        corridor(h,true);var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner,new Vec3(3.5,2,3.5));var actor=m.actor(entry.id);actor.getInventory().setItem(0,new ItemStack(Items.COBBLESTONE,8));int[] attempts={0};
        class Deny{@net.minecraftforge.eventbus.api.SubscribeEvent public void place(net.minecraftforge.event.level.BlockEvent.EntityPlaceEvent e){if(e.getEntity()!=null && e.getEntity().getUUID().equals(entry.id)){attempts[0]++;e.setCanceled(true);}}}
        var deny=new Deny();net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(deny);var work=travel(h,m,owner,entry,new Vec3(10.5,2,3.5));
        h.onEachTick(()->{if(work.state()!=JobState.FAILED)return;net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(deny);h.assertTrue(attempts[0]==1 && actor.getInventory().countItem(Items.COBBLESTONE)==8 && !actor.isShiftKeyDown() && h.getLevel().getBlockState(h.absolutePos(new BlockPos(5,1,3))).isAir(),"Native denied bridge attempt rolled back blocks/items and released crouching");clear(m,owner,entry);pass("navigation_bridge_native_denial_cleanup");h.succeed();});
    }
    @GameTest(batch="navigation",template="empty",timeoutTicks=1600)
    public static void independentTravelGeneratesChunksAndReleasesNativeTracking(GameTestHelper h){
        var m=manager(h);UUID owner=UUID.randomUUID();var level=h.getLevel();BlockPos start=new BlockPos(24000,70,24000);Vec3 source=Vec3.atBottomCenterOf(start),destination=source.add(216,0,0);var chunk=new net.minecraft.world.level.ChunkPos(BlockPos.containing(destination));boolean initiallyLoaded=level.getChunkSource().getChunkNow(chunk.x,chunk.z)!=null;
        for(int x=0;x<=4;x++)level.setBlockAndUpdate(start.offset(x,-1,0),Blocks.STONE.defaultBlockState());
        var entry=m.spawn(owner,level,source,"QL"+UUID.randomUUID().toString().substring(0,8));var actor=m.actor(entry.id);var work=m.jobs().submitProgram(owner,entry.id,entry.authority.session(),UUID.randomUUID(),JsonParser.parseString("{\"steps\":[{\"op\":\"travel\",\"destination\":"+JobJson.vector(destination)+"}]}").getAsJsonObject(),"replace");double[] distance={0};Vec3[] previous={source};
        boolean[] generatedAhead={false};h.onEachTick(()->{
            if(actor.getX()<chunk.getMinBlockX()-8 && level.getChunkSource().getChunkNow(chunk.x,chunk.z)!=null)generatedAhead[0]=true;
            // Supply a narrow fixture road only four blocks ahead, after the native avatar loader has made each chunk full.
            for(int x=Math.max(start.getX(),actor.blockPosition().getX()-1);x<=Math.min(start.getX()+220,actor.blockPosition().getX()+4);x++){BlockPos support=new BlockPos(x,start.getY()-1,start.getZ());if(level.getChunkSource().getChunkNow(x>>4,start.getZ()>>4)!=null)level.setBlockAndUpdate(support,Blocks.STONE.defaultBlockState());}
            distance[0]+=actor.position().distanceTo(previous[0]);previous[0]=actor.position();check(h,work);if(work.state()!=JobState.SUCCEEDED)return;
            h.assertTrue(!initiallyLoaded && generatedAhead[0] && level.getChunkSource().getChunkNow(chunk.x,chunk.z)!=null && distance[0]>210 && actor.position().distanceTo(destination)<0.71,"Native avatar loading generated the absent destination chunk before the incremental fixture road touched it");
            h.assertTrue(level.getServer().getPlayerList().getPlayer(owner)==null && level.getChunkSource().chunkMap.getPlayers(chunk,false).contains(actor),"Native avatar tracking works without an owner body nearby");clear(m,owner,entry);h.assertTrue(!level.getChunkSource().chunkMap.getPlayers(chunk,false).contains(actor),"Native tracking is released on removal");pass("independent_long_travel_generation_tracking_release");h.succeed();});
    }
    @GameTest(batch="terrain_recovery",template="empty",timeoutTicks=300)
    public static void blockedRouteUsesActualToolAndBreaksObstructions(GameTestHelper h){
        corridor(h,false);for(int y=2;y<=3;y++)h.getLevel().setBlockAndUpdate(h.absolutePos(new BlockPos(6,y,3)),Blocks.DIRT.defaultBlockState());
        var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner,new Vec3(3.5,2,3.5));var actor=m.actor(entry.id);actor.getInventory().setItem(4,new ItemStack(Items.IRON_SHOVEL));
        var work=travel(h,m,owner,entry,new Vec3(10.5,2,3.5));
        h.onEachTick(()->{check(h,work);if(work.state()!=JobState.SUCCEEDED)return;
            h.assertTrue(h.getLevel().getBlockState(h.absolutePos(new BlockPos(6,2,3))).isAir() && h.getLevel().getBlockState(h.absolutePos(new BlockPos(6,3,3))).isAir(),"Native route recovery removed both actual obstructions");
            h.assertTrue(actor.getMainHandItem().is(Items.IRON_SHOVEL) && actor.getMainHandItem().getDamageValue()==2,"Effective carried tool took normal durability");clear(m,owner,entry);pass("navigation_native_obstruction_breaking");h.succeed();});
    }
    @GameTest(batch="terrain_recovery",template="empty",timeoutTicks=330)
    public static void missingFloorIsBridgedUsingActualCarriedBlocks(GameTestHelper h){
        corridor(h,true);var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner,new Vec3(3.5,2,3.5));var actor=m.actor(entry.id);actor.getInventory().setItem(4,new ItemStack(Items.COBBLESTONE,8));
        var work=travel(h,m,owner,entry,new Vec3(10.5,2,3.5));
        h.onEachTick(()->{check(h,work);if(work.state()!=JobState.SUCCEEDED)return;int placed=0;for(int x=5;x<=7;x++)if(h.getLevel().getBlockState(h.absolutePos(new BlockPos(x,1,3))).is(Blocks.COBBLESTONE))placed++;
            h.assertTrue(placed==3 && actor.getInventory().countItem(Items.COBBLESTONE)==5 && !actor.isShiftKeyDown(),"Three real bridge blocks consumed once and restored sneaking state");clear(m,owner,entry);pass("navigation_native_bridge_consumption");h.succeed();});
    }
    @GameTest(batch="terrain_recovery",template="empty",timeoutTicks=250)
    public static void verticalRoutePillarsWithNativePlacementAndStableArrival(GameTestHelper h){
        floor(h);var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner,new Vec3(3.5,2,3.5));var actor=m.actor(entry.id);actor.getInventory().setItem(0,new ItemStack(Items.COBBLESTONE,8));
        var work=travel(h,m,owner,entry,new Vec3(3.5,5,3.5));boolean[] finished={false};
        h.onEachTick(()->{if(finished[0])return;check(h,work);if(work.state()!=JobState.SUCCEEDED)return;finished[0]=true;
            h.runAfterDelay(12,()->{int placed=0;for(int y=2;y<5;y++)if(h.getLevel().getBlockState(h.absolutePos(new BlockPos(3,y,3))).is(Blocks.COBBLESTONE))placed++;h.assertTrue(placed==3 && actor.getInventory().countItem(Items.COBBLESTONE)==5 && Math.abs(actor.getY()-h.absoluteVec(new Vec3(3.5,5,3.5)).y)<0.1,"Stable arrival stands on three actually consumed pillar blocks");clear(m,owner,entry);pass("navigation_native_pillar_stable_arrival");h.succeed();});});
    }
    @GameTest(batch="navigation",template="empty",timeoutTicks=170)
    public static void actualFallPhysicsUsesNativePlayerDamageChecks(GameTestHelper h){
        floor(h);BlockPos support=h.absolutePos(new BlockPos(3,15,3));h.getLevel().setBlockAndUpdate(support,Blocks.STONE.defaultBlockState());var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner,new Vec3(3.5,16,3.5));var actor=m.actor(entry.id);actor.getFoodData().setFoodLevel(10);boolean[] dropped={false};long start=h.getLevel().getGameTime();
        h.onEachTick(()->{if(!dropped[0] && h.getLevel().getGameTime()-start>=70){dropped[0]=true;h.getLevel().setBlockAndUpdate(support,Blocks.AIR.defaultBlockState());}
            if(h.getLevel().getGameTime()-start>=160)h.assertTrue(actor.getHealth()<20,"Native fall did not damage: position="+actor.position()+" ground="+actor.onGround()+" water="+actor.isInWater()+" health="+actor.getHealth());
            if(!dropped[0] || actor.getHealth()>=20)return;h.assertTrue(actor.isAlive() && actor.getHealth()<=10 && actor.onGround(),"Native landing applies actual nonlethal survival fall damage");clear(m,owner,entry);pass("native_local_player_fall_damage");h.succeed();});
    }
    private static AvatarManager manager(GameTestHelper h){return ServerEvents.avatars(h.getLevel().getServer());}
    private static AvatarEntry spawn(GameTestHelper h,AvatarManager m,UUID owner,Vec3 relative){return m.spawn(owner,h.getLevel(),h.absoluteVec(relative),"QN"+UUID.randomUUID().toString().substring(0,8));}
    private static GameJob travel(GameTestHelper h,AvatarManager m,UUID owner,AvatarEntry entry,Vec3 relative){return m.jobs().submitProgram(owner,entry.id,entry.authority.session(),UUID.randomUUID(),JsonParser.parseString("{\"steps\":[{\"op\":\"travel\",\"destination\":"+JobJson.vector(h.absoluteVec(relative))+"}]} ").getAsJsonObject(),"replace");}
    private static void clear(AvatarManager m,UUID owner,AvatarEntry entry){m.actor(entry.id).getInventory().clearContent();m.destroy(owner,entry.id);}
    private static void check(GameTestHelper h,GameJob work){h.assertTrue(work.state()!=JobState.FAILED && work.state()!=JobState.CANCELLED,"Navigation failed: "+work.view());}
    private static void floor(GameTestHelper h){for(int x=0;x<14;x++)for(int z=0;z<14;z++)h.getLevel().setBlockAndUpdate(h.absolutePos(new BlockPos(x,1,z)),Blocks.STONE.defaultBlockState());}
    private static void set(GameTestHelper h, int x, int y, int z, net.minecraft.world.level.block.state.BlockState state) {
        h.getLevel().setBlockAndUpdate(h.absolutePos(new BlockPos(x, y, z)), state);
    }
    private static void corridor(GameTestHelper h,boolean gap){
        floor(h);for(int x=0;x<14;x++)for(int y=2;y<=4;y++){h.getLevel().setBlockAndUpdate(h.absolutePos(new BlockPos(x,y,2)),Blocks.BEDROCK.defaultBlockState());h.getLevel().setBlockAndUpdate(h.absolutePos(new BlockPos(x,y,4)),Blocks.BEDROCK.defaultBlockState());h.getLevel().setBlockAndUpdate(h.absolutePos(new BlockPos(x,4,3)),Blocks.BEDROCK.defaultBlockState());}
        for(int x:new int[]{0,13})for(int y=2;y<=4;y++)h.getLevel().setBlockAndUpdate(h.absolutePos(new BlockPos(x,y,3)),Blocks.BEDROCK.defaultBlockState());
        if(gap)for(int x=5;x<=7;x++)for(int y=-3;y<=1;y++)h.getLevel().setBlockAndUpdate(h.absolutePos(new BlockPos(x,y,3)),Blocks.AIR.defaultBlockState());
    }
    private static void pass(String event){Paracraft.LOG.info("QA_EVT {}","{\"event\":\""+event+"\",\"status\":\"pass\"}");}
}

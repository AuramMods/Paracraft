package com.aurammods.paracraft.qa;

import com.aurammods.paracraft.Paracraft;
import com.aurammods.paracraft.forge.ServerEvents;
import com.aurammods.paracraft.forge.avatar.AvatarEntry;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.ArrayList;
import java.util.UUID;

@GameTestHolder(Paracraft.ID)
@PrefixGameTestTemplate(false)
public final class CapacityGameTests {
    @GameTest(batch="capacity",template="arena",timeoutTicks=200)
    public static void nativeCapacityLimitsReconfigurationAndReconnectAreEnforced(GameTestHelper h){
        var m=ServerEvents.avatars(h.getLevel().getServer());var made=new ArrayList<AvatarEntry>();UUID owner=UUID.randomUUID();int ownerLimit=Paracraft.OWNER_LIMIT.get(),serverLimit=Paracraft.SERVER_LIMIT.get();
        try{
            h.assertTrue(ownerLimit==4 && serverLimit==64,"QA starts with configured production defaults");
            java.util.function.Function<UUID,AvatarEntry> spawn=who->{int index=made.size();var entry=m.spawn(who,h.getLevel(),h.absoluteVec(new Vec3(3.5+(index%8)*4,2,3.5+(index/8)*4)),"QL"+UUID.randomUUID().toString().substring(0,8));made.add(entry);return entry;};
            for(int i=0;i<4;i++)spawn.apply(owner);m.actor(made.get(0).id).getInventory().setItem(0,new ItemStack(Items.DIAMOND,7));
            try{spawn.apply(owner);throw new AssertionError("Fifth saved avatar should fail under actual owner limit");}catch(IllegalStateException expected){h.assertTrue(expected.getMessage().contains("Owner"),"Actual owner admission returned its reason");}
            while(m.entries().stream().filter(e->m.actor(e.id)!=null).count()<64)spawn.apply(UUID.randomUUID());
            try{spawn.apply(UUID.randomUUID());throw new AssertionError("Sixty-fifth active avatar should fail");}catch(IllegalStateException expected){h.assertTrue(expected.getMessage().contains("Active"),"Actual server admission returned its reason");}
            Paracraft.OWNER_LIMIT.set(5);Paracraft.SERVER_LIMIT.set(65);var extra=spawn.apply(owner);h.assertTrue(m.actor(extra.id)!=null && h.getLevel().getServer().getPlayerList().getPlayer(extra.id)==m.actor(extra.id),"Actual changed config permits the additional native body");
            m.destroy(owner,extra.id);made.remove(extra);Paracraft.OWNER_LIMIT.set(ownerLimit);Paracraft.SERVER_LIMIT.set(serverLimit);
            var owners=made.stream().filter(e->e.authority.owner().equals(owner)).toList();m.disconnectOwner(owner);h.assertTrue(owners.size()==4 && owners.stream().allMatch(e->m.actor(e.id)==null),"Disconnect releases all four bodies while saving their entries");
            var fillers=new ArrayList<AvatarEntry>();for(int i=0;i<4;i++)fillers.add(spawn.apply(UUID.randomUUID()));
            var full=m.restoreOwner(owner);h.assertTrue(full.size()==4 && owners.stream().allMatch(e->m.actor(e.id)==null),"Reconnect at capacity reports actual failures without creating partial native bodies");
            for(var filler:fillers){m.destroy(filler.authority.owner(),filler.id);made.remove(filler);}
            h.assertTrue(m.restoreOwner(owner).isEmpty() && owners.stream().allMatch(e->m.actor(e.id)!=null) && m.actor(owners.get(0).id).getInventory().countItem(Items.DIAMOND)==7,"Freeing slots permits restoration with belongings conserved");
            h.assertTrue(m.entries().stream().filter(e->m.actor(e.id)!=null).count()==64,"Actual simultaneous native companion count is exactly the configured capacity");
            Paracraft.LOG.info("QA_EVT {}","{\"event\":\"native_capacity_4_64_config_and_reconnect\",\"status\":\"pass\",\"context\":{\"activeAvatars\":64,\"inference\":false,\"loadBenchmark\":false}}");h.succeed();
        }finally{
            Paracraft.OWNER_LIMIT.set(ownerLimit);Paracraft.SERVER_LIMIT.set(serverLimit);
            for(var entry:made){var actor=m.actor(entry.id);if(actor!=null)actor.getInventory().clearContent();m.destroy(entry.authority.owner(),entry.id);}
        }
    }
}

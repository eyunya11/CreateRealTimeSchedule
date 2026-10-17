package io.github.eyunya11.createrealtimeschedule.schedule;

import com.simibubi.create.Create;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Hooks the wait condition into the game: Create's list of conditions, and the memory of booked slots. */
@EventBusSubscriber(modid = Createrealtimeschedule.MODID)
public final class ScheduleSetup {

    private ScheduleSetup() {}

    /**
     * Runs on both sides: a client has to know the condition to read a schedule that holds it, and
     * the list must be complete before any world, and the schedules saved in it, is loaded.
     */
    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(RealTimeOfDayCondition::register);
    }

    /**
     * Forgets the slots of trains that no longer exist, such as ones that were taken apart. With no
     * trains at all there is nothing to compare against (Create may simply not have read them yet),
     * and what is left over is harmless, so nothing is forgotten then.
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!Create.RAILWAYS.trains.isEmpty())
            TrainSlotMemory.get(event.getServer()).retainOnly(Create.RAILWAYS.trains.keySet());
    }
}

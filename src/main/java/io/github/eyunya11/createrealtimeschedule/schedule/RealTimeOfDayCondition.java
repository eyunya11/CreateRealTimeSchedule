package io.github.eyunya11.createrealtimeschedule.schedule;

import java.util.List;
import java.util.OptionalLong;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.apache.commons.lang3.mutable.MutableObject;

import com.google.common.collect.ImmutableList;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.condition.ScheduleWaitCondition;
import com.simibubi.create.foundation.gui.ModularGuiLineBuilder;
import com.simibubi.create.foundation.gui.widget.Label;
import com.simibubi.create.foundation.gui.widget.ScrollInput;
import com.simibubi.create.foundation.utility.CreateLang;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.clock.ClockService;
import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import net.createmod.catnip.data.Pair;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * "Real time of day": the train leaves at a time of the real-time clock, and a train that arrives
 * late leaves after a minimum stop instead (see {@link DeparturePlanner} for the rule).
 *
 * <p>It asks for the same things as Create's time-of-day condition, a time and how often it
 * repeats, plus the minimum stop. It does not extend that condition, because it shares nothing
 * with it but the look of the settings, and anything that treats Create's condition specially
 * should not take this one for it.
 *
 * <p>The departure time is worked out once, when the train starts to wait, and kept in the
 * condition's context as a clock value, so it survives a restart, and a clock that is set to
 * another time is followed by trains that are already waiting.
 */
public class RealTimeOfDayCondition extends ScheduleWaitCondition {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Createrealtimeschedule.MODID, "real_time_of_day");

    /** The keys of the condition's settings. The first three are the ones Create's time-of-day condition uses. */
    public static final String HOUR = "Hour";
    public static final String MINUTE = "Minute";
    public static final String ROTATION = "Rotation";
    public static final String MINIMUM_STOP = "MinimumStop";

    /** The keys of what a waiting train keeps in the condition's context. */
    public static final String CONTEXT_DEPARTURE = "Departure";
    public static final String CONTEXT_SLOT = "Slot";

    public static final int DEFAULT_HOUR = 8;
    public static final int DEFAULT_MINIMUM_STOP_SECONDS = 20;
    public static final int MAX_MINIMUM_STOP_SECONDS = 600;

    private static final String LANG = ID.getNamespace() + ".schedule.condition." + ID.getPath();
    private static final String[] ROTATION_NAMES = {
        "every_24", "every_12", "every_6", "every_4", "every_3", "every_2", "every_1", "every_0_45", "every_0_30", "every_0_15"
    };

    /** What a stop's booking came to. */
    public record Booking(long slot, long departure) {}

    public RealTimeOfDayCondition() {
        this(DEFAULT_HOUR, 0, 0, DEFAULT_MINIMUM_STOP_SECONDS);
    }

    /**
     * @param rotation the index of the "repeat every" choice, 0 for daily (see {@link RealClock#rotationTicks})
     */
    public RealTimeOfDayCondition(int hour, int minute, int rotation, int minimumStopSeconds) {
        data.putInt(HOUR, hour);
        data.putInt(MINUTE, minute);
        data.putInt(ROTATION, rotation);
        data.putInt(MINIMUM_STOP, minimumStopSeconds);
    }

    /** Adds the condition to Create's list of wait conditions, once; Create's screen and saving then handle it. */
    public static void register() {
        for (Pair<ResourceLocation, Supplier<? extends ScheduleWaitCondition>> type : Schedule.CONDITION_TYPES)
            if (type.getFirst().equals(ID))
                return;
        Supplier<? extends ScheduleWaitCondition> factory = RealTimeOfDayCondition::new;
        Schedule.CONDITION_TYPES.add(Pair.of(ID, factory));
    }

    // ------------------------------------------------------------------ the settings

    public int hour() {
        return Mth.clamp(intData(HOUR), 0, 23);
    }

    public int minute() {
        return Mth.clamp(intData(MINUTE), 0, 59);
    }

    /** The time of day the slots fall on, in clock ticks. */
    public long timeOfDayTicks() {
        return RealClock.ticksOf(hour(), minute(), 0);
    }

    public long rotationTicks() {
        return RealClock.rotationTicks(intData(ROTATION));
    }

    public long minimumStopTicks() {
        return Math.max(0, intData(MINIMUM_STOP)) * (long) RealClock.TICKS_PER_SECOND;
    }

    // ------------------------------------------------------------------ waiting

    @Override
    public boolean tickCompletion(Level level, Train train, CompoundTag context) {
        if (!(level instanceof ServerLevel serverLevel))
            return false;
        long now = ClockService.now(serverLevel.getServer());
        if (!context.contains(CONTEXT_DEPARTURE, Tag.TAG_LONG)) {
            Booking booking = book(serverLevel, train, now);
            context.putLong(CONTEXT_SLOT, booking.slot());
            context.putLong(CONTEXT_DEPARTURE, booking.departure());
            requestStatusToUpdate(context);
        }
        return now >= context.getLong(CONTEXT_DEPARTURE);
    }

    /**
     * Works out when the train that has just started to wait here leaves, and remembers the slot it
     * took for the next stop.
     */
    public Booking book(ServerLevel level, Train train, long arrival) {
        TrainSlotMemory memory = TrainSlotMemory.get(level.getServer());
        int signature = signatureOf(level, train);
        OptionalLong previous = DeparturePlanner.previousSlot(memory.get(train.id), signature, arrival);
        long slot = DeparturePlanner.chooseSlot(previous, arrival, timeOfDayTicks(), rotationTicks());
        memory.put(train.id, DeparturePlanner.remember(slot, arrival, previous, signature));
        return new Booking(slot, DeparturePlanner.departure(slot, arrival, minimumStopTicks()));
    }

    /**
     * Identifies the schedule a train is running, so that a slot booked under another schedule is
     * not chained off. Where the train is in the schedule is left out, because that changes every
     * time the schedule is taken out and put back.
     */
    public static int signatureOf(Level level, Train train) {
        Schedule schedule = train.runtime.getSchedule();
        if (schedule == null)
            return 0;
        CompoundTag tag = schedule.write(level.registryAccess());
        tag.remove("Progress");
        return tag.hashCode();
    }

    @Override
    public MutableComponent getWaitingStatus(Level level, Train train, CompoundTag tag) {
        // A train that has not been booked yet has no departure time; the time asked for is the best there is.
        String time = tag.contains(CONTEXT_DEPARTURE, Tag.TAG_LONG)
            ? RealClock.format(RealClock.timeOfDay(tag.getLong(CONTEXT_DEPARTURE)))
            : timeText(hour(), minute());
        return Component.translatable(LANG + ".status", time);
    }

    // ------------------------------------------------------------------ how it is shown

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    public Pair<ItemStack, Component> getSummary() {
        return Pair.of(new ItemStack(Items.STRUCTURE_VOID), Component.literal(timeText(hour(), minute())));
    }

    @Override
    public List<Component> getTitleAs(String type) {
        return ImmutableList.of(Component.translatable(LANG),
            Component.literal(timeText(hour(), minute())).withStyle(ChatFormatting.DARK_AQUA)
                .append(Component.literal(" -> ").withStyle(ChatFormatting.DARK_GRAY))
                .append(rotationOption(intData(ROTATION)).copy().withStyle(ChatFormatting.GRAY)),
            Component.translatable(LANG + ".minimum_stop", formatSeconds(intData(MINIMUM_STOP)))
                .withStyle(ChatFormatting.GRAY));
    }

    /** {@code HH:MM}, the way the settings show the time. */
    public static String timeText(int hour, int minute) {
        return String.format("%02d:%02d", hour, minute);
    }

    /** The minimum stop in the room the settings have for it: {@code 45s}, {@code 2m}, {@code 1m30s}. */
    public static String formatSeconds(int seconds) {
        int minutes = seconds / 60;
        int rest = seconds % 60;
        if (minutes == 0)
            return rest + "s";
        return rest == 0 ? minutes + "m" : minutes + "m" + rest + "s";
    }

    public static Component rotationOption(int index) {
        return Component.translatable(LANG + ".rotation." + ROTATION_NAMES[Mth.clamp(index, 0, ROTATION_NAMES.length - 1)]);
    }

    private static List<Component> rotationOptions() {
        return IntStream.range(0, RealClock.ROTATION_CHOICES).mapToObj(RealTimeOfDayCondition::rotationOption).toList();
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public boolean renderSpecialIcon(GuiGraphics graphics, int x, int y) {
        // The same clock face as Create's time-of-day condition, which starts its frames at noon.
        int displayHour = (hour() + 12) % 24;
        float progress = (displayHour * 60f + minute()) / (24 * 60);
        String frame = String.format("%02d", Mth.clamp((int) (progress * 64), 0, 63));
        ResourceLocation location = ResourceLocation.withDefaultNamespace("textures/item/clock_" + frame + ".png");
        graphics.blit(location, x, y, 0, 0, 0, 16, 16, 16, 16);
        return true;
    }

    /**
     * The one line of settings the schedule screen has room for: the time (scroll over the hours and
     * over the minutes), the minimum stop, and how often it repeats.
     */
    @Override
    @OnlyIn(Dist.CLIENT)
    public void initConfigurationWidgets(ModularGuiLineBuilder builder) {
        MutableObject<ScrollInput> hourInput = new MutableObject<>();
        MutableObject<ScrollInput> minuteInput = new MutableObject<>();
        MutableObject<Label> timeLabel = new MutableObject<>();

        builder.addScrollInput(0, 19, (i, l) -> {
            i.withRange(0, 24);
            timeLabel.setValue(l);
            hourInput.setValue(i);
        }, HOUR);

        builder.addScrollInput(19, 18, (i, l) -> {
            i.withRange(0, 60);
            minuteInput.setValue(i);
            l.visible = false;
        }, MINUTE);

        builder.addScrollInput(40, 36, (i, l) -> {
            i.withRange(0, MAX_MINIMUM_STOP_SECONDS + 1)
                .withStepFunction(context -> context.shift ? 30 : 5)
                .withShiftStep(30)
                .format(seconds -> Component.literal(formatSeconds(seconds)))
                .titled(Component.translatable(LANG + ".minimum_stop.title"))
                .addHint(Component.translatable(LANG + ".minimum_stop.hint"));
            i.lockedTooltipX = -15;
            i.lockedTooltipY = 35;
        }, MINIMUM_STOP);

        builder.addSelectionScrollInput(78, 43, (i, l) -> {
            i.forOptions(rotationOptions())
                .titled(Component.translatable(LANG + ".rotation"));
        }, ROTATION);

        hourInput.getValue()
            .titled(CreateLang.translateDirect("generic.daytime.hour"))
            .calling(t -> {
                data.putInt(HOUR, t);
                timeLabel.getValue().text = Component.literal(timeText(t, minuteInput.getValue().getState()));
            })
            .writingTo(null)
            .withShiftStep(6);

        minuteInput.getValue()
            .titled(CreateLang.translateDirect("generic.daytime.minute"))
            .calling(t -> {
                data.putInt(MINUTE, t);
                timeLabel.getValue().text = Component.literal(timeText(hourInput.getValue().getState(), t));
            })
            .writingTo(null)
            .withShiftStep(15);

        minuteInput.getValue().lockedTooltipX = hourInput.getValue().lockedTooltipX = -15;
        minuteInput.getValue().lockedTooltipY = hourInput.getValue().lockedTooltipY = 35;

        hourInput.getValue().setState(intData(HOUR));
        minuteInput.getValue().setState(intData(MINUTE)).onChanged();

        builder.customArea(0, 38);
        builder.customArea(40, 36);
        builder.customArea(78, 43);
    }
}

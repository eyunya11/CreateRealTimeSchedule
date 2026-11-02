package io.github.eyunya11.createrealtimeschedule.gametest;

import java.util.List;

import com.simibubi.create.content.trains.graph.DimensionPalette;
import com.simibubi.create.content.trains.graph.TrackGraph;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.gametest.TrackLine.Turn;
import io.github.eyunya11.createrealtimeschedule.railway.CreateNetwork;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork;
import io.github.eyunya11.createrealtimeschedule.railway.TagSections;
import io.github.eyunya11.createrealtimeschedule.tag.TrackTagBlockEntity;
import io.github.eyunya11.createrealtimeschedule.tag.TrackTagEdgePoint;
import io.github.eyunya11.createrealtimeschedule.tag.TrackTagService;
import io.github.eyunya11.createrealtimeschedule.tag.TrackTags;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Track tag blocks of both kinds on real track, with {@code ./gradlew runGameTestServer}: the point
 * each makes on Create's graph, and the sections that {@link TagSections} reads from them. Each test has a batch of
 * its own (see {@link TrackLine}).
 */
@GameTestHolder(Createrealtimeschedule.MODID)
@PrefixGameTestTemplate(false)
public class TrackTagTests {

    private static final String TURN = "track_turn";
    /**
     * How long a test leaves its track and blocks once it has seen what it is for, so that a player
     * who runs it on a client can look at them and use their screens.
     */
    private static final int LOOK_TICKS = 3 * 60 * 20;
    private static final int TIMEOUT_TICKS = 400 + LOOK_TICKS;
    private static final Turn BRANCH = new Turn(60, 72, 14, 55);
    /** How far a section's length may be from the blocks' x apart: the nodes at the ends of the line are not on a block's centre. */
    private static final double SLACK = 1;

    /**
     * A straight line, which Create splits into edges at the chunk borders, with three blocks for both
     * ways beside it: at x = 20 facing east with the tag Local, at 50 facing east with none, and at 40
     * facing west with Rapid. The first block's section runs east to the second, and the third's west to
     * the end of the line, over it: from 20 to 40 the track has both tags, which is no problem.
     */
    @GameTest(template = TURN, batch = "realtimetag_sections", timeoutTicks = TIMEOUT_TICKS)
    public static void aSectionRunsToTheNextBlockFacingTheSameWayForTrainsBothWays(GameTestHelper helper) {
        TrackLine line = new TrackLine(helper, 80, new int[] {5, 75}, new String[] {"A", "B"});
        line.cleanUpWhenTheTestEnds();
        TrackTagBlockEntity[] blocks = new TrackTagBlockEntity[3];
        int[] tags = new int[2];
        int[] looked = {-1};
        helper.onEachTick(() -> {
            if (!line.setUp() || lookingOn(helper, looked))
                return;
            if (blocks[0] == null) {
                tags[0] = line.makeTag("Local (test)");
                tags[1] = line.makeTag("Rapid (test)");
                blocks[0] = line.placeTag(20, true, false, tags[0]);
                blocks[1] = line.placeTag(50, true, false);
                blocks[2] = line.placeTag(40, false, false, tags[1]);
                return;
            }
            if (!onTrack(blocks))
                return;
            TrackGraph graph = line.train().graph;
            RailNetwork network = CreateNetwork.read(List.of(graph));
            helper.assertTrue(network.markers().size() == 3, "3 tag blocks on the track, not " + network.markers().size());
            TagSections sections = TagSections.of(network);
            int east = marker(helper, network, blocks[0]);
            int west = marker(helper, network, blocks[2]);
            // A section for both ways is on the edges both ways, so the length tagged for it is twice its own.
            assertLength(helper, sections, network, east, 2 * (50 - 20), "the eastward Local block's");
            assertLength(helper, sections, network, west, 2 * (40 - 0), "the westward Rapid block's");
            assertLength(helper, sections, network, marker(helper, network, blocks[1]), 0, "the block with no tag's");
            assertOverlap(helper, sections, network, east, west, 2 * (40 - 20));
            helper.assertTrue(sections.problems().isEmpty(), "sections of two tags facing two ways may overlap: " + sections.problems());

            // The westward block gets both tags.
            long before = CreateNetwork.fingerprint(List.of(graph));
            blocks[2].setTags(tags[1], tags[0]);
            helper.assertTrue(CreateNetwork.fingerprint(List.of(graph)) != before, "a tag changed is a change of the track");
            List<Integer> both = TrackTags.ids(tags);
            RailNetwork changed = CreateNetwork.read(List.of(graph));
            List<Integer> westTags = changed.markers().get(marker(helper, changed, blocks[2])).tags();
            helper.assertTrue(westTags.equals(both), "the westward block has both tags, not " + westTags);
            helper.assertTrue(TrackTagService.uses(tags[0]) == 2 && TrackTagService.uses(tags[1]) == 1,
                "two blocks have Local and one Rapid: " + TrackTagService.uses(tags[0]) + " and " + TrackTagService.uses(tags[1]));

            // The point keeps its tags and its kind with Create's graph, saved and read again.
            TrackTagEdgePoint read = savedAndRead(helper, blocks[2].edgePoint.getEdgePoint());
            helper.assertTrue(read.tags().equals(both) && !read.oneWay(), "the point read again has the tags " + read.tags()
                + (read.oneWay() ? ", one way" : "") + ", not " + both + " for both ways");
            startLooking(helper, looked, blocks);
        });
    }

    /**
     * One-way blocks on a straight line: Up at x = 20 facing east and Down at 60 facing west, on the
     * same track. Each section is only on the edges the way its block faces, and the two are no problem.
     * A block for both ways at 40 then mixes the two kinds, in both sections.
     */
    @GameTest(template = TURN, batch = "realtimetag_one_way", timeoutTicks = TIMEOUT_TICKS)
    public static void aOneWaySectionHoldsOnlyForTrainsRunningTheWayItsBlockFaces(GameTestHelper helper) {
        TrackLine line = new TrackLine(helper, 80, new int[] {5, 75}, new String[] {"A", "B"});
        line.cleanUpWhenTheTestEnds();
        TrackTagBlockEntity[] blocks = new TrackTagBlockEntity[3];
        int[] looked = {-1};
        helper.onEachTick(() -> {
            if (!line.setUp() || lookingOn(helper, looked))
                return;
            if (blocks[0] == null) {
                blocks[0] = line.placeTag(20, true, true, line.makeTag("Up (test)"));
                blocks[1] = line.placeTag(60, false, true, line.makeTag("Down (test)"));
                return;
            }
            if (blocks[2] == null) {
                if (!onTrack(blocks[0], blocks[1]))
                    return;
                RailNetwork network = CreateNetwork.read(List.of(line.train().graph));
                TagSections sections = TagSections.of(network);
                helper.assertTrue(network.markers().stream().allMatch(RailNetwork.Marker::oneWay), "both blocks are one-way: " + network.markers());
                // A one-way section is on the edges one way only, so the length tagged for it is its own.
                assertLength(helper, sections, network, marker(helper, network, blocks[0]), 80 - 20, "the eastward Up block's");
                assertLength(helper, sections, network, marker(helper, network, blocks[1]), 60 - 0, "the westward Down block's");
                helper.assertTrue(sections.problems().isEmpty(), "one-way sections facing two ways may overlap: " + sections.problems());
                TrackTagEdgePoint read = savedAndRead(helper, blocks[0].edgePoint.getEdgePoint());
                helper.assertTrue(read.oneWay(), "the point read again is one-way");
                blocks[2] = line.placeTag(40, true, false, line.makeTag("Main (test)"));
                return;
            }
            if (!onTrack(blocks[2]))
                return;
            RailNetwork network = CreateNetwork.read(List.of(line.train().graph));
            List<TagSections.Problem> problems = TagSections.of(network).problems();
            int both = marker(helper, network, blocks[2]);
            helper.assertTrue(problems.size() == 2 && problems.stream().allMatch(problem -> problem.kind() == TagSections.Kind.MIXED
                && (problem.marker() == both || problem.other() == both)),
                "the block for both ways is mixed with each one-way block: " + problems);
            startLooking(helper, looked, blocks);
        });
    }

    /** A block facing east on the first row, with no other block before the junction where the branch turns off: a problem. */
    @GameTest(template = TURN, batch = "realtimetag_junction", timeoutTicks = TIMEOUT_TICKS)
    public static void aSectionThatComesToAJunctionIsAProblem(GameTestHelper helper) {
        TrackLine line = new TrackLine(helper, 115, BRANCH,
            new BlockPos[] {at(6, TrackLine.TRACK_Z), at(110, TrackLine.TRACK_Z), at(BRANCH.toX(), 51)}, new String[] {"Depot", "A", "B"});
        line.cleanUpWhenTheTestEnds();
        TrackTagBlockEntity[] block = new TrackTagBlockEntity[1];
        int[] looked = {-1};
        helper.onEachTick(() -> {
            if (!line.setUp() || lookingOn(helper, looked))
                return;
            if (block[0] == null) {
                block[0] = line.placeTag(30, true, false, line.makeTag("Main (test)"));
                return;
            }
            if (block[0].edgePoint.getEdgePoint() == null)
                return;
            RailNetwork network = CreateNetwork.read(List.of(line.train().graph));
            TagSections sections = TagSections.of(network);
            List<TagSections.Problem> problems = sections.problems();
            helper.assertTrue(problems.size() == 1 && problems.get(0).kind() == TagSections.Kind.JUNCTION,
                "one junction, not " + problems);
            RailNetwork.Place node = problems.get(0).node();
            double junctionX = helper.absolutePos(at(BRANCH.fromX(), TrackLine.TRACK_Z)).getX() + 0.5;
            helper.assertTrue(node != null && Math.abs(node.x() - junctionX) <= 1.5,
                "the junction is where the branch turns off, at x = " + junctionX + ", not " + node);
            assertLength(helper, sections, network, 0, 2 * (BRANCH.fromX() - 30), "the section up to the junction");
            // The block's screen tells of it, from the block's own track graph.
            List<Component> said = TrackTagService.problems(block[0]);
            helper.assertTrue(said.size() == 1 && said.get(0).getString().contains("junction"), "the screen says " + said);
            startLooking(helper, looked, block);
        });
    }

    /**
     * Starts the time the track is left up, telling the players in the level (on a client, the one who
     * ran the test) where the tag blocks are.
     */
    private static void startLooking(GameTestHelper helper, int[] looked, TrackTagBlockEntity... blocks) {
        looked[0] = 0;
        StringBuilder where = new StringBuilder();
        for (TrackTagBlockEntity block : blocks)
            where.append(where.isEmpty() ? "" : "; ").append(block.getBlockPos().toShortString());
        Component message = Component.literal(helper.testInfo.getTestName() + ": the tag blocks are at " + where
            + ", and stay for " + LOOK_TICKS / 20 + " seconds");
        for (ServerPlayer player : helper.getLevel().players())
            player.sendSystemMessage(message);
    }

    /**
     * Whether the test has seen what it is for and is leaving the track up to be looked at; it succeeds
     * after {@link #LOOK_TICKS}. {@code looked} holds the ticks since, or -1 before then.
     */
    private static boolean lookingOn(GameTestHelper helper, int[] looked) {
        if (looked[0] < 0)
            return false;
        if (++looked[0] >= LOOK_TICKS)
            helper.succeed();
        return true;
    }

    private static BlockPos at(int x, int z) {
        return new BlockPos(x, TrackLine.TRACK_Y, z);
    }

    /** The index of the marker of {@code block} in {@code network}. */
    private static int marker(GameTestHelper helper, RailNetwork network, TrackTagBlockEntity block) {
        BlockPos pos = block.getBlockPos();
        List<RailNetwork.Marker> markers = network.markers();
        for (int i = 0; i < markers.size(); i++) {
            RailNetwork.Place place = markers.get(i).block();
            if (place.x() == pos.getX() && place.y() == pos.getY() && place.z() == pos.getZ())
                return i;
        }
        throw new IllegalStateException("no tag block at " + pos.toShortString() + " in the network");
    }

    /** Whether the blocks have all made their points on the graph. */
    private static boolean onTrack(TrackTagBlockEntity... blocks) {
        for (TrackTagBlockEntity block : blocks)
            if (block.edgePoint.getEdgePoint() == null)
                return false;
        return true;
    }

    /** {@code point} saved as Create saves its graph, and read again. */
    private static TrackTagEdgePoint savedAndRead(GameTestHelper helper, TrackTagEdgePoint point) {
        DimensionPalette palette = new DimensionPalette();
        CompoundTag saved = new CompoundTag();
        point.write(saved, helper.getLevel().registryAccess(), palette);
        TrackTagEdgePoint read = new TrackTagEdgePoint();
        read.read(saved, helper.getLevel().registryAccess(), false, palette);
        return read;
    }

    /** The length the sections of blocks {@code a} and {@code b} overlap, over all the edges, is {@code expected}. */
    private static void assertOverlap(GameTestHelper helper, TagSections sections, RailNetwork network, int a, int b, double expected) {
        double length = 0;
        for (int edge = 0; edge < network.edges().size(); edge++)
            for (TagSections.Span one : sections.spans(edge))
                for (TagSections.Span other : sections.spans(edge))
                    if (one.marker() == a && other.marker() == b)
                        length += Math.max(0, Math.min(one.to(), other.to()) - Math.max(one.from(), other.from()));
        helper.assertTrue(Math.abs(length - expected) <= 2 * SLACK, "the sections overlap " + length + " long both ways, not " + expected);
    }

    /** The length tagged for the section of block {@code marker}, over all the edges, is {@code expected}. */
    private static void assertLength(GameTestHelper helper, TagSections sections, RailNetwork network, int marker, double expected, String what) {
        double length = 0;
        for (int edge = 0; edge < network.edges().size(); edge++)
            for (TagSections.Span span : sections.spans(edge))
                if (span.marker() == marker)
                    length += span.to() - span.from();
        helper.assertTrue(Math.abs(length - expected) <= 2 * SLACK, what + " section is " + length + " long both ways, not " + expected);
    }
}

/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal;

import org.gridgain.grid.*;
import org.gridgain.grid.compute.*;
import org.gridgain.grid.events.*;
import org.gridgain.grid.lang.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.testframework.junits.common.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.gridgain.grid.events.GridEventType.*;

/**
 * Event storage tests.
 *
 * Note:
 * Test based on events generated by test task execution.
 * Filter class must be static because it will be send to remote host in
 * serialized form.
 */
@GridCommonTest(group = "Kernal Self")
public class GridEventStorageSelfTest extends GridCommonAbstractTest {
    /** First grid. */
    private static Grid grid1;

    /** Second grid. */
    private static Grid grid2;

    /** */
    public GridEventStorageSelfTest() {
        super(/*start grid*/false);
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        grid1 = startGrid(1);
        grid2 = startGrid(2);
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        stopAllGrids();
    }

    /**
     * @throws Exception In case of error.
     */
    public void testAddRemoveGlobalListener() throws Exception {
        GridPredicate<GridEvent> lsnr = new GridPredicate<GridEvent>() {
            @Override public boolean apply(GridEvent evt) {
                info("Received local event: " + evt);

                return true;
            }
        };

        grid1.events().localListen(lsnr, EVTS_ALL_MINUS_METRIC_UPDATE);

        assert grid1.events().stopLocalListen(lsnr);
    }

    /**
     * @throws Exception In case of error.
     */
    public void testAddRemoveDiscoListener() throws Exception {
        GridPredicate<GridEvent> lsnr = new GridPredicate<GridEvent>() {
            @Override public boolean apply(GridEvent evt) {
                info("Received local event: " + evt);

                return true;
            }
        };

        grid1.events().localListen(lsnr, EVT_NODE_LEFT, EVT_NODE_FAILED);

        assert grid1.events().stopLocalListen(lsnr);
        assert !grid1.events().stopLocalListen(lsnr);
    }

    /**
     * @throws Exception In case of error.
     */
    public void testLocalNodeEventStorage() throws Exception {
        TestEventListener lsnr = new TestEventListener();

        GridPredicate<GridEvent> filter = new TestEventFilter();

        // Check that two same listeners may be added.
        grid1.events().localListen(lsnr, EVT_TASK_STARTED);
        grid1.events().localListen(lsnr, EVT_TASK_STARTED);

        // Execute task.
        generateEvents(grid1);

        assert lsnr.getCounter() == 1;

        Collection<GridEvent> evts = grid1.events().localQuery(filter);

        assert evts != null;
        assert evts.size() == 1;

        // Execute task.
        generateEvents(grid1);

        // Check that listener has been removed.
        assert lsnr.getCounter() == 2;

        // Check that no problems with nonexistent listeners.
        assert grid1.events().stopLocalListen(lsnr);
        assert !grid1.events().stopLocalListen(lsnr);

        // Check for events from local node.
        evts = grid1.events().localQuery(filter);

        assert evts != null;
        assert evts.size() == 2;

        // Check for events from empty remote nodes collection.
        try {
            grid1.forPredicate(F.<GridNode>alwaysFalse()).events().remoteQuery(filter, 0);
        }
        catch (GridEmptyProjectionException ignored) {
            // No-op
        }
    }

    /**
     * @throws Exception In case of error.
     */
    public void testRemoteNodeEventStorage() throws Exception {
        GridPredicate<GridEvent> filter = new TestEventFilter();

        generateEvents(grid2);

        Collection<GridEvent> evts = grid1.forPredicate(F.remoteNodes(grid1.localNode().id())).
            events().remoteQuery(filter, 0);

        assert evts != null;
        assert evts.size() == 1;
    }

    /**
     * @throws Exception In case of error.
     */
    public void testRemoteAndLocalNodeEventStorage() throws Exception {
        GridPredicate<GridEvent> filter = new TestEventFilter();

        generateEvents(grid1);

        Collection<GridEvent> evts = grid1.events().remoteQuery(filter, 0);
        Collection<GridEvent> locEvts = grid1.events().localQuery(filter);
        Collection<GridEvent> remEvts = grid1.forPredicate(F.remoteNodes(grid1.localNode().id())).
            events().remoteQuery(filter, 0);

        assert evts != null;
        assert locEvts != null;
        assert remEvts != null;
        assert evts.size() == 1;
        assert locEvts.size() == 1;
        assert remEvts.isEmpty();
    }

    /**
     * Create events in grid.
     *
     * @param grid Grid.
     * @throws GridException In case of error.
     */
    private void generateEvents(GridProjection grid) throws GridException {
        grid.compute().localDeployTask(GridEventTestTask.class, GridEventTestTask.class.getClassLoader());

        grid.compute().execute(GridEventTestTask.class.getName(), null);
    }

    /**
     * Test task.
     */
    private static class GridEventTestTask extends GridComputeTaskSplitAdapter<Object, Object> {
        /** {@inheritDoc} */
        @Override protected Collection<? extends GridComputeJob> split(int gridSize, Object arg) throws GridException {
            return Collections.singleton(new GridEventTestJob());
        }

        /** {@inheritDoc} */
        @Override public Serializable reduce(List<GridComputeJobResult> results) throws GridException {
            assert results != null;
            assert results.size() == 1;

            return results.get(0).getData();
        }
    }

    /**
     * Test job.
     */
    private static class GridEventTestJob extends GridComputeJobAdapter {
        /** {@inheritDoc} */
        @Override public String execute() throws GridException {
            return "GridEventTestJob-test-event.";
        }
    }

    /**
     * Test event listener.
     */
    private class TestEventListener implements GridPredicate<GridEvent> {
        /** Event counter. */
        private AtomicInteger cnt = new AtomicInteger();

        /** {@inheritDoc} */
        @Override public boolean apply(GridEvent evt) {
            info("Event storage event: evt=" + evt);

            // Count only started tasks.
            if (evt.type() == EVT_TASK_STARTED)
                cnt.incrementAndGet();

            return true;
        }

        /**
         * @return Event counter value.
         */
        public int getCounter() {
            return cnt.get();
        }

        /**
         * Clear event counter.
         */
        public void clearCounter() {
            cnt.set(0);
        }
    }

    /**
     * Test event filter.
     */
    private static class TestEventFilter implements GridPredicate<GridEvent> {
        /** {@inheritDoc} */
        @Override public boolean apply(GridEvent evt) {
            // Accept only predefined TASK_STARTED events.
            return evt.type() == EVT_TASK_STARTED;
        }
    }
}

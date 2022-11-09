/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.transaction.state.storeview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.neo4j.function.Predicates.ALWAYS_TRUE_INT;
import static org.neo4j.graphdb.IndexingTestUtil.assertOnlyDefaultTokenIndexesExists;
import static org.neo4j.graphdb.IndexingTestUtil.dropTokenIndexes;
import static org.neo4j.io.pagecache.context.EmptyVersionContextSupplier.EMPTY;
import static org.neo4j.memory.EmptyMemoryTracker.INSTANCE;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.neo4j.configuration.Config;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.internal.batchimport.Configuration;
import org.neo4j.io.pagecache.context.CursorContextFactory;
import org.neo4j.io.pagecache.tracing.DefaultPageCacheTracer;
import org.neo4j.kernel.impl.api.index.IndexingService;
import org.neo4j.kernel.impl.api.index.StoreScan;
import org.neo4j.kernel.impl.api.index.TokenScanConsumer;
import org.neo4j.kernel.impl.locking.Locks;
import org.neo4j.kernel.impl.newapi.Operations;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.lock.LockService;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.scheduler.JobScheduler;
import org.neo4j.storageengine.api.StorageEngine;
import org.neo4j.test.Barrier;
import org.neo4j.test.OtherThreadExecutor;
import org.neo4j.test.Race;
import org.neo4j.test.RandomSupport;
import org.neo4j.test.extension.DbmsExtension;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.RandomExtension;
import org.neo4j.token.TokenHolders;

@ExtendWith(RandomExtension.class)
@DbmsExtension
public class DynamicIndexStoreViewIT {
    private static final Label PERSON = Label.label("person");
    private static final RelationshipType FRIEND = RelationshipType.withName("friend");

    @Inject
    private RandomSupport random;

    @Inject
    private GraphDatabaseAPI database;

    @Inject
    private LockService lockService;

    @Inject
    private Locks locks;

    @Inject
    private IndexingService indexingService;

    @Inject
    private StorageEngine storageEngine;

    @Inject
    private TokenHolders tokenHolders;

    @Inject
    private JobScheduler scheduler;

    private DynamicIndexStoreView storeView;

    @BeforeEach
    void setUp() {
        assertOnlyDefaultTokenIndexesExists(database);
        storeView = new DynamicIndexStoreView(
                new FullScanStoreView(lockService, storageEngine, Config.defaults(), scheduler),
                locks,
                lockService,
                Config.defaults(),
                indexDescriptor -> indexingService.getIndexProxy(indexDescriptor),
                storageEngine,
                NullLogProvider.getInstance());
    }

    @Test
    void shouldHandleConcurrentDeletionOfTokenIndexDuringNodeScan() {
        shouldHandleConcurrentDeletionOfTokenIndexDuringScan(this::populateNodes, this::nodeStoreScan);
    }

    @Test
    void shouldHandleConcurrentDeletionOfTokenIndexDuringRelationshipScan() {
        shouldHandleConcurrentDeletionOfTokenIndexDuringScan(this::populateRelationships, this::relationshipStoreScan);
    }

    private void shouldHandleConcurrentDeletionOfTokenIndexDuringScan(
            LongSupplier entitiesCreator, Function<TokenScanConsumer, StoreScan> storeScanSupplier) {
        // Given
        var entities = entitiesCreator.getAsLong();
        var consumer = new TestTokenScanConsumer();
        var storeScan = storeScanSupplier.apply(consumer);
        var scanSuccessful = new AtomicBoolean();

        // When
        Race race = new Race().withRandomStartDelays();
        race.addContestant(() -> dropSafeRun(storeScan, scanSuccessful));
        race.addContestant(() -> dropTokenIndexes(database), 5);
        race.goUnchecked();

        // Then
        if (scanSuccessful.get()) {
            assertScanCompleted(storeScan, consumer, entities);
        }
        storeScan.stop();
    }

    @Test
    void nodeLookupIndexDropShouldAwaitStoreScanFinish() throws Exception {
        lookupIndexDropShouldAwaitStoreScanFinish(this::populateNodes, this::nodeStoreScan);
    }

    @Test
    void relationshipLookupIndexDropShouldAwaitStoreScanFinish() throws Exception {
        lookupIndexDropShouldAwaitStoreScanFinish(this::populateRelationships, this::relationshipStoreScan);
    }

    private void lookupIndexDropShouldAwaitStoreScanFinish(
            LongSupplier entitiesCreator, Function<TokenScanConsumer, StoreScan> storeScanSupplier) throws Exception {
        // Given
        var entities = entitiesCreator.getAsLong();
        var barrier = new Barrier.Control();
        var consumer = new TestTokenScanConsumer(new TestTokenScanConsumer.Monitor() {
            private final AtomicBoolean first = new AtomicBoolean(true);

            @Override
            public void recordAdded(long entityId, long[] tokens) {
                if (first.getAndSet(false)) {
                    barrier.reached();
                }
            }
        });
        var storeScan = storeScanSupplier.apply(consumer);

        // When
        try (var t2 = new OtherThreadExecutor("T2");
                var t3 = new OtherThreadExecutor("T3")) {
            var scan = t2.executeDontWait(() -> {
                storeScan.run(new ContainsExternalUpdates());
                return null;
            });
            var drop = t3.executeDontWait(() -> {
                dropTokenIndexes(database);
                return null;
            });
            t3.waitUntilWaiting(waitDetails -> waitDetails.isAt(Operations.class, "indexDrop"));
            barrier.release();
            scan.get();
            drop.get();
        }

        // Then
        assertScanCompleted(storeScan, consumer, entities);
        storeScan.stop();
    }

    private static void assertScanCompleted(StoreScan storeScan, TestTokenScanConsumer consumer, long entities) {
        assertThat(storeScan.getProgress().getProgress()).isEqualTo(1.0f);
        assertThat(consumer.consumedEntities()).isEqualTo(entities);
    }

    private void dropSafeRun(StoreScan storeScan, AtomicBoolean scanSuccessful) {
        try {
            storeScan.run(new ContainsExternalUpdates());
            scanSuccessful.set(true);
        } catch (IllegalStateException e) {
            // If this scan starts after the index has been dropped this exception will be thrown
            scanSuccessful.set(false);
        }
    }

    private class ContainsExternalUpdates implements StoreScan.ExternalUpdatesCheck {
        @Override
        public boolean needToApplyExternalUpdates() {
            return random.nextBoolean();
        }

        @Override
        public void applyExternalUpdates(long id) {}
    }

    private StoreScan nodeStoreScan(TokenScanConsumer consumer) {
        CursorContextFactory contextFactory = new CursorContextFactory(new DefaultPageCacheTracer(), EMPTY);
        return storeView.visitNodes(
                getLabelIds(), ALWAYS_TRUE_INT, null, consumer, false, true, contextFactory, INSTANCE);
    }

    private StoreScan relationshipStoreScan(TokenScanConsumer consumer) {
        CursorContextFactory contextFactory = new CursorContextFactory(new DefaultPageCacheTracer(), EMPTY);
        return storeView.visitRelationships(
                getRelationTypeIds(), ALWAYS_TRUE_INT, null, consumer, false, true, contextFactory, INSTANCE);
    }

    private int[] getLabelIds() {
        return new int[] {tokenHolders.labelTokens().getIdByName(PERSON.name())};
    }

    private int[] getRelationTypeIds() {
        return new int[] {tokenHolders.relationshipTypeTokens().getIdByName(FRIEND.name())};
    }

    private long populateNodes() {
        long nodes = Configuration.DEFAULT.batchSize() + 100;
        try (var tx = database.beginTx()) {
            for (int i = 0; i < nodes; i++) {
                tx.createNode(PERSON);
            }
            tx.commit();
        }
        return nodes;
    }

    private long populateRelationships() {
        long relations = Configuration.DEFAULT.batchSize() + 100;
        try (var tx = database.beginTx()) {
            for (int i = 0; i < relations; i++) {
                tx.createNode(PERSON).createRelationshipTo(tx.createNode(PERSON), FRIEND);
            }
            tx.commit();
        }
        return relations;
    }
}

package org.infinispan.interceptors.impl;

import org.infinispan.Cache;
import org.infinispan.CacheSet;
import org.infinispan.cache.impl.Caches;
import org.infinispan.commands.FlagAffectedCommand;
import org.infinispan.commands.LocalFlagAffectedCommand;
import org.infinispan.commands.functional.ReadOnlyKeyCommand;
import org.infinispan.commands.functional.ReadWriteKeyCommand;
import org.infinispan.commands.functional.ReadWriteKeyValueCommand;
import org.infinispan.commands.read.AbstractDataCommand;
import org.infinispan.commands.read.EntrySetCommand;
import org.infinispan.commands.read.GetAllCommand;
import org.infinispan.commands.read.GetCacheEntryCommand;
import org.infinispan.commands.read.GetKeyValueCommand;
import org.infinispan.commands.read.KeySetCommand;
import org.infinispan.commands.remote.GetKeysInGroupCommand;
import org.infinispan.commands.write.ApplyDeltaCommand;
import org.infinispan.commands.write.InvalidateCommand;
import org.infinispan.commands.write.PutKeyValueCommand;
import org.infinispan.commands.write.RemoveCommand;
import org.infinispan.commands.write.ReplaceCommand;
import org.infinispan.commands.write.WriteCommand;
import org.infinispan.commons.equivalence.Equivalence;
import org.infinispan.commons.equivalence.EquivalentHashSet;
import org.infinispan.commons.util.CloseableIterator;
import org.infinispan.commons.util.CloseableSpliterator;
import org.infinispan.commons.util.Closeables;
import org.infinispan.container.DataContainer;
import org.infinispan.container.EntryFactory;
import org.infinispan.container.InternalEntryFactory;
import org.infinispan.container.entries.CacheEntry;
import org.infinispan.container.entries.InternalCacheEntry;
import org.infinispan.context.Flag;
import org.infinispan.context.InvocationContext;
import org.infinispan.distribution.group.GroupFilter;
import org.infinispan.distribution.group.GroupManager;
import org.infinispan.factories.annotations.ComponentName;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.annotations.Start;
import org.infinispan.filter.CollectionKeyFilter;
import org.infinispan.filter.CompositeKeyFilter;
import org.infinispan.filter.KeyFilter;
import org.infinispan.jmx.annotations.DisplayType;
import org.infinispan.jmx.annotations.MBean;
import org.infinispan.jmx.annotations.ManagedAttribute;
import org.infinispan.jmx.annotations.ManagedOperation;
import org.infinispan.jmx.annotations.MeasurementType;
import org.infinispan.jmx.annotations.Parameter;
import org.infinispan.marshall.core.MarshalledEntry;
import org.infinispan.notifications.cachelistener.CacheNotifier;
import org.infinispan.persistence.PersistenceUtil;
import org.infinispan.persistence.manager.PersistenceManager;
import org.infinispan.persistence.spi.AdvancedCacheLoader;
import org.infinispan.persistence.util.PersistenceManagerCloseableSupplier;
import org.infinispan.stream.impl.interceptor.AbstractDelegatingEntryCacheSet;
import org.infinispan.stream.impl.interceptor.AbstractDelegatingKeyCacheSet;
import org.infinispan.stream.impl.spliterators.IteratorAsSpliterator;
import org.infinispan.util.CloseableSuppliedIterator;
import org.infinispan.util.DistinctKeyDoubleEntryCloseableIterator;
import org.infinispan.util.TimeService;
import org.infinispan.util.function.CloseableSupplier;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.infinispan.factories.KnownComponentNames.PERSISTENCE_EXECUTOR;
import static org.infinispan.persistence.PersistenceUtil.convert;

/**
 * @since 9.0
 */
@MBean(objectName = "CacheLoader", description = "Component that handles loading entries from a CacheStore into memory.")
public class CacheLoaderInterceptor<K, V> extends JmxStatsCommandInterceptor {
   private final AtomicLong cacheLoads = new AtomicLong(0);
   private final AtomicLong cacheMisses = new AtomicLong(0);

   protected PersistenceManager persistenceManager;
   protected CacheNotifier notifier;
   protected volatile boolean enabled = true;
   protected EntryFactory entryFactory;
   private TimeService timeService;
   private InternalEntryFactory iceFactory;
   private DataContainer<K, V> dataContainer;
   private GroupManager groupManager;
   private ExecutorService executorService;
   private Cache<K, V> cache;
   private Equivalence<? super K> keyEquivalence;

   private static final Log log = LogFactory.getLog(CacheLoaderInterceptor.class);
   private static final boolean trace = log.isTraceEnabled();

   @Inject
   protected void injectDependencies(PersistenceManager clm, EntryFactory entryFactory, CacheNotifier notifier,
                                     TimeService timeService, InternalEntryFactory iceFactory, DataContainer<K, V> dataContainer,
                                     GroupManager groupManager, @ComponentName(PERSISTENCE_EXECUTOR) ExecutorService persistenceExecutor,
                                     Cache<K, V> cache) {
      this.persistenceManager = clm;
      this.notifier = notifier;
      this.entryFactory = entryFactory;
      this.timeService = timeService;
      this.iceFactory = iceFactory;
      this.dataContainer = dataContainer;
      this.groupManager = groupManager;
      this.executorService = persistenceExecutor;
      this.cache = cache;
   }

   @Start
   public void start() {
      this.keyEquivalence = cache.getCacheConfiguration().dataContainer().keyEquivalence();
   }

   @Override
   public CompletableFuture<Void> visitApplyDeltaCommand(InvocationContext ctx, ApplyDeltaCommand command)
         throws Throwable {
      return visitDataCommand(ctx, command);
   }

   @Override
   public CompletableFuture<Void> visitPutKeyValueCommand(InvocationContext ctx, PutKeyValueCommand command)
         throws Throwable {
      return visitDataCommand(ctx, command);
   }

   @Override
   public CompletableFuture<Void> visitGetKeyValueCommand(InvocationContext ctx, GetKeyValueCommand command)
         throws Throwable {
      return visitDataCommand(ctx, command);
   }

   @Override
   public CompletableFuture<Void> visitGetCacheEntryCommand(InvocationContext ctx,
         GetCacheEntryCommand command) throws Throwable {
      return visitDataCommand(ctx, command);
   }


   @Override
   public CompletableFuture<Void> visitGetAllCommand(InvocationContext ctx, GetAllCommand command)
         throws Throwable {
      if (enabled) {
         for (Object key : command.getKeys()) {
            loadIfNeeded(ctx, key, command);
         }
      }
      return ctx.continueInvocation();
   }

   @Override
   public CompletableFuture<Void> visitInvalidateCommand(InvocationContext ctx, InvalidateCommand command)
         throws Throwable {
      if (enabled) {
         Object[] keys;
         if ((keys = command.getKeys()) != null && keys.length > 0) {
            for (Object key : command.getKeys()) {
               loadIfNeeded(ctx, key, command);
            }
         }
      }
      return ctx.continueInvocation();
   }

   @Override
   public CompletableFuture<Void> visitRemoveCommand(InvocationContext ctx, RemoveCommand command)
         throws Throwable {
      return visitDataCommand(ctx, command);
   }

   @Override
   public CompletableFuture<Void> visitReplaceCommand(InvocationContext ctx, ReplaceCommand command)
         throws Throwable {
      return visitDataCommand(ctx, command);
   }

   private CompletableFuture<Void> visitDataCommand(InvocationContext ctx, AbstractDataCommand command)
         throws Throwable {
      if (enabled) {
         Object key;
         if ((key = command.getKey()) != null) {
            loadIfNeeded(ctx, key, command);
         }
      }
      return ctx.continueInvocation();
   }

   @Override
   public CompletableFuture<Void> visitGetKeysInGroupCommand(final InvocationContext ctx,
         GetKeysInGroupCommand command) throws Throwable {
      final String groupName = command.getGroupName();
      if (!command.isGroupOwner() || !enabled || hasSkipLoadFlag(command)) {
         return ctx.continueInvocation();
      }

      final KeyFilter<Object> keyFilter = new CompositeKeyFilter<>(new GroupFilter<>(groupName, groupManager),
            new CollectionKeyFilter<>(ctx.getLookedUpEntries().keySet()));
      persistenceManager.processOnAllStores(keyFilter, new AdvancedCacheLoader.CacheLoaderTask() {
         @Override
         public void processEntry(MarshalledEntry marshalledEntry, AdvancedCacheLoader.TaskContext taskContext) throws InterruptedException {
            synchronized (ctx) {
               //the process can be made in multiple threads, so we need to synchronize in the context.
               entryFactory.wrapExternalEntry(ctx, marshalledEntry.getKey(),
                                              convert(marshalledEntry, iceFactory), EntryFactory.Wrap.STORE,
                                              false);
            }
         }
      }, true, true);
      return ctx.continueInvocation();
   }

   @Override
   public CompletableFuture<Void> visitEntrySetCommand(InvocationContext ctx, EntrySetCommand command)
         throws Throwable {
      return ctx.onReturn((rCtx, rCommand, rv, throwable) -> {
         if (throwable != null || !enabled || hasSkipLoadFlag(command)) {
            // Continue with the existing throwable/return value
            return null;
         }
         CacheSet<CacheEntry<K, V>> entrySet = (CacheSet<CacheEntry<K, V>>) rv;
         CacheSet<CacheEntry<K, V>> wrappedEntrySet = new WrappedEntrySet(command, entrySet);
         return CompletableFuture.completedFuture(wrappedEntrySet);
      });
   }

   class SupplierFunction<K, V> implements CloseableSupplier<K> {
      private final CloseableSupplier<CacheEntry<K, V>> supplier;

      SupplierFunction(CloseableSupplier<CacheEntry<K, V>> supplier) {
         this.supplier = supplier;
      }

      @Override
      public K get() {
         CacheEntry<K, V> entry = supplier.get();
         if (entry != null) {
            return entry.getKey();
         }
         return null;
      }

      @Override
      public void close() {
         supplier.close();
      }
   }

   @Override
   public CompletableFuture<Void> visitKeySetCommand(InvocationContext ctx, KeySetCommand command)
         throws Throwable {
      return ctx.onReturn((rCtx, rCommand, rv, throwable) -> {
         if (throwable != null || !enabled || hasSkipLoadFlag(command)) {
            // Continue with the existing throwable/return value
            return null;
         }

         CacheSet<K> keySet = (CacheSet<K>) rv;
         CacheSet<K> wrappedKeySet = new WrappedKeySet(command, keySet);
         return CompletableFuture.completedFuture(wrappedKeySet);
      });
   }

   @Override
   public CompletableFuture<Void> visitReadOnlyKeyCommand(InvocationContext ctx, ReadOnlyKeyCommand command) throws Throwable {
      return visitDataCommand(ctx, command);
   }

   @Override
   public CompletableFuture<Void> visitReadWriteKeyCommand(InvocationContext ctx, ReadWriteKeyCommand command)
         throws Throwable {
      return visitDataCommand(ctx, command);
   }

   @Override
   public CompletableFuture<Void> visitReadWriteKeyValueCommand(InvocationContext ctx, ReadWriteKeyValueCommand command)
         throws Throwable {
      return visitDataCommand(ctx, command);
   }

   protected final boolean isConditional(WriteCommand cmd) {
      return cmd.isConditional();
   }

   protected final boolean hasSkipLoadFlag(LocalFlagAffectedCommand cmd) {
      return cmd.hasFlag(Flag.SKIP_CACHE_LOAD);
   }

   protected boolean canLoad(Object key) {
      return true;
   }

   /**
    * Loads from the cache loader the entry for the given key.  A found value is loaded into the current context.  The
    * method returns whether the value was found or not, or even if the cache loader was checked.
    * @param ctx The current invocation's context
    * @param key The key for the entry to look up
    * @param cmd The command that was called that now wants to query the cache loader
    * @return Whether or not the entry was found in the cache loader.  A value of null means the cache loader was never
    * queried for the value, so it was neither a hit or a miss.
    * @throws Throwable
    */
   protected final Boolean loadIfNeeded(final InvocationContext ctx, Object key, final FlagAffectedCommand cmd) {
      if (skipLoad(cmd, key, ctx)) {
         return null;
      }

      return loadInContext(ctx, key, cmd);
   }

   private Boolean loadInContext(InvocationContext ctx, Object key, FlagAffectedCommand cmd) {
      final AtomicReference<Boolean> isLoaded = new AtomicReference<>();
      InternalCacheEntry<K, V> entry = PersistenceUtil.loadAndStoreInDataContainer(dataContainer, persistenceManager, (K) key,
                                                                             ctx, timeService, isLoaded);
      Boolean isLoadedValue = isLoaded.get();
      if (trace) {
         log.tracef("Entry was loaded? %s", isLoadedValue);
      }
      if (getStatisticsEnabled()) {
         if (isLoadedValue == null) {
            // the entry was in data container, we haven't touched cache store
         } else if (isLoadedValue) {
            cacheLoads.incrementAndGet();
         } else {
            cacheMisses.incrementAndGet();
         }
      }

      if (entry != null) {
         EntryFactory.Wrap wrap =
               cmd instanceof WriteCommand ? EntryFactory.Wrap.WRAP_NON_NULL : EntryFactory.Wrap.STORE;
         entryFactory.wrapExternalEntry(ctx, key, entry, wrap, !cmd.readsExistingValues());

         if (isLoadedValue != null && isLoadedValue.booleanValue()) {
            Object value = entry.getValue();
            // FIXME: There's no point to trigger the entryLoaded/Activated event twice.
            sendNotification(key, value, true, ctx, cmd);
            sendNotification(key, value, false, ctx, cmd);
         }
      }
      return isLoadedValue;
   }

   private boolean skipLoad(FlagAffectedCommand cmd, Object key, InvocationContext ctx) {
      if (!shouldAttemptLookup(ctx.lookupEntry(key))) {
         if (trace) {
            log.tracef("Skip load for command %s. Entry already exists in context.", cmd);
         }
         return true;
      }

      if (!canLoad(key)) {
         if (trace) {
            log.tracef("Skip load for command %s. Cannot load the key.", cmd);
         }
         return true;
      }

      boolean skip;
      if (cmd instanceof WriteCommand) {
         skip = skipLoadForWriteCommand((WriteCommand) cmd, key, ctx);
         if (trace) {
            log.tracef("Skip load for write command %s? %s", cmd, skip);
         }
      } else {
         //read command
         skip = hasSkipLoadFlag(cmd);
         if (trace) {
            log.tracef("Skip load for command %s?. %s", cmd, skip);
         }
      }
      return skip;
   }

   protected boolean skipLoadForWriteCommand(WriteCommand cmd, Object key, InvocationContext ctx) {
      if (cmd.readsExistingValues()) {
         // TODO Could make DELTA_WRITE/ApplyDeltaCommand override SKIP_CACHE_LOAD by changing the next line to
         // if (hasSkipLoadFlag(cmd) && !cmd.alwaysReadsExistingValues)
         if (hasSkipLoadFlag(cmd)) {
            log.tracef("Skipping load for command that reads existing values %s", cmd);
            return true;
         } else {
            return false;
         }
      }
      return true;
   }

   /**
    * Only perform if context doesn't have a value found (Read Committed) or if we can do a remote
    * get only if the value is null (Repeatable Read)
    */
   private boolean shouldAttemptLookup(CacheEntry e) {
      return e == null || (e.isNull() || e.getValue() == null) && !e.skipLookup();
   }

   protected void sendNotification(Object key, Object value, boolean pre,
         InvocationContext ctx, FlagAffectedCommand cmd) {
      notifier.notifyCacheEntryLoaded(key, value, pre, ctx, cmd);
   }

   @ManagedAttribute(
         description = "Number of entries loaded from cache store",
         displayName = "Number of cache store loads",
         measurementType = MeasurementType.TRENDSUP
   )
   @SuppressWarnings("unused")
   public long getCacheLoaderLoads() {
      return cacheLoads.get();
   }

   @ManagedAttribute(
         description = "Number of entries that did not exist in cache store",
         displayName = "Number of cache store load misses",
         measurementType = MeasurementType.TRENDSUP
   )
   @SuppressWarnings("unused")
   public long getCacheLoaderMisses() {
      return cacheMisses.get();
   }

   @Override
   @ManagedOperation(
         description = "Resets statistics gathered by this component",
         displayName = "Reset Statistics"
   )
   public void resetStatistics() {
      cacheLoads.set(0);
      cacheMisses.set(0);
   }

   @ManagedAttribute(
         description = "Returns a collection of cache loader types which are configured and enabled",
         displayName = "Returns a collection of cache loader types which are configured and enabled",
         displayType = DisplayType.DETAIL)
   /**
    * This method returns a collection of cache loader types (fully qualified class names) that are configured and enabled.
    */
   public Collection<String> getStores() {
      if (enabled && cacheConfiguration.persistence().usingStores()) {
         return persistenceManager.getStoresAsString();
      } else {
         return Collections.emptySet();
      }
   }

   @ManagedOperation(
         description = "Disable all stores of a given type, where type is a fully qualified class name of the cache loader to disable",
         displayName = "Disable all stores of a given type"
   )
   @SuppressWarnings("unused")
   /**
    * Disables a store of a given type.
    *
    * If the given type cannot be found, this is a no-op.  If more than one store of the same type is configured,
    * all stores of the given type are disabled.
    *
    * @param storeType fully qualified class name of the cache loader type to disable
    */
   public void disableStore(@Parameter(name = "storeType", description = "Fully qualified class name of a store implementation") String storeType) {
      if (enabled) persistenceManager.disableStore(storeType);
   }

   public void disableInterceptor() {
      enabled = false;
   }

   private class WrappedEntrySet extends AbstractDelegatingEntryCacheSet<K, V> {
      private final CacheSet<CacheEntry<K, V>> entrySet;

      public WrappedEntrySet(EntrySetCommand command, CacheSet<CacheEntry<K, V>> entrySet) {
         super(Caches.getCacheWithFlags(CacheLoaderInterceptor.this.cache, command), entrySet);
         this.entrySet = entrySet;
      }

      @Override
      public CloseableIterator<CacheEntry<K, V>> iterator() {
         CloseableIterator<CacheEntry<K, V>> iterator = Closeables.iterator(entrySet.stream());
         Set<K> seenKeys =
               new EquivalentHashSet<K>(cache.getAdvancedCache().getDataContainer().size(), keyEquivalence);
         // TODO: how to handle concurrent activation....
         return new DistinctKeyDoubleEntryCloseableIterator<>(iterator, new CloseableSuppliedIterator<>(

               // TODO: how to pass in key filter...
               new PersistenceManagerCloseableSupplier<>(executorService, persistenceManager, iceFactory,
                     new CollectionKeyFilter<>(seenKeys), 10, TimeUnit.SECONDS, 2048)), e -> e.getKey(),
               seenKeys);
      }

      @Override
      public CloseableSpliterator<CacheEntry<K, V>> spliterator() {
         return spliteratorFromIterator(iterator());
      }

      private <E> CloseableSpliterator<E> spliteratorFromIterator(CloseableIterator<E> iterator) {
         return new IteratorAsSpliterator.Builder<>(iterator)
               .setCharacteristics(Spliterator.CONCURRENT | Spliterator.DISTINCT | Spliterator.NONNULL).get();
      }

      @Override
      public int size() {
         long size = stream().count();
         if (size > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
         }
         return (int) size;
      }
   }

   private class WrappedKeySet extends AbstractDelegatingKeyCacheSet<K, V> {

      private final CacheSet<K> keySet;

      public WrappedKeySet(KeySetCommand command, CacheSet<K> keySet) {
         super(Caches.getCacheWithFlags(CacheLoaderInterceptor.this.cache, command), keySet);
         this.keySet = keySet;
      }

      @Override
      public CloseableIterator<K> iterator() {
         CloseableIterator<K> iterator = Closeables.iterator(keySet.stream());
         Set<K> seenKeys = new EquivalentHashSet<K>(cache.getAdvancedCache().getDataContainer().size(),
               keyEquivalence);
         // TODO: how to handle concurrent activation....
         return new DistinctKeyDoubleEntryCloseableIterator<>(iterator, new CloseableSuppliedIterator<>(new SupplierFunction<>(
               new PersistenceManagerCloseableSupplier<>(executorService, persistenceManager,
                     // TODO: how to pass in key filter...
                     iceFactory, new CollectionKeyFilter<>(seenKeys), 10, TimeUnit.SECONDS, 2048))),
               Function.identity(), seenKeys);
      }

      @Override
      public CloseableSpliterator<K> spliterator() {
         return spliteratorFromIterator(iterator());
      }

      private <E> CloseableSpliterator<E> spliteratorFromIterator(CloseableIterator<E> iterator) {
         return new IteratorAsSpliterator.Builder<>(iterator).setCharacteristics(
               Spliterator.CONCURRENT | Spliterator.DISTINCT | Spliterator.NONNULL).get();
      }

      @Override
      public int size() {
         long size = stream().count();
         if (size > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
         }
         return (int) size;
      }
   }
}

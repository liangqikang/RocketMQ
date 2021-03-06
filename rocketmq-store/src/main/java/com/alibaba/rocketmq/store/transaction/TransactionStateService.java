/**
 * $Id: TransactionStateService.java 1831 2013-05-16 01:39:51Z shijia.wxr $
 */
package com.alibaba.rocketmq.store.transaction;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.rocketmq.common.Message;
import com.alibaba.rocketmq.common.MessageExt;
import com.alibaba.rocketmq.common.MixAll;
import com.alibaba.rocketmq.common.sysflag.MessageSysFlag;
import com.alibaba.rocketmq.store.ConsumeQueue;
import com.alibaba.rocketmq.store.DefaultMessageStore;
import com.alibaba.rocketmq.store.MapedFile;
import com.alibaba.rocketmq.store.MapedFileQueue;
import com.alibaba.rocketmq.store.SelectMapedBufferResult;


/**
 * 事务服务，存储每条事务的状态（Prepared，Commited，Rollbacked）<br>
 * 名词解释：<br>
 * clOffset - Commit Log Offset<br>
 * tsOffset - Transaction State Table Offset
 * 
 * @author shijia.wxr<vintage.wang@gmail.com>
 * 
 */
public class TransactionStateService {
    private static final Logger log = LoggerFactory.getLogger(MixAll.StoreLoggerName);
    // 存储单元大小
    public static final int TSStoreUnitSize = 24;
    // 用来恢复事务状态表的redolog
    public static final String TRANSACTION_REDOLOG_TOPIC = "TRANSACTION_REDOLOG_TOPIC_XXXX";
    public static final int TRANSACTION_REDOLOG_TOPIC_QUEUEID = 0;
    // 存储顶层对象
    private final DefaultMessageStore defaultMessageStore;
    // 存储事务状态的表格
    private MapedFileQueue tranStateTable;
    // 重复利用内存Buffer
    private final ByteBuffer byteBufferAppend = ByteBuffer.allocate(TSStoreUnitSize);
    // 事务状态的Redolog，当进程意外宕掉，可通过redolog恢复所有事务的状态
    // Redolog的实现利用了消费队列，主要为了恢复方便
    private final ConsumeQueue tranRedoLog;
    public final static long PreparedMessageTagsCode = -1;

    // 更改事务状态，具体更改位置
    private final static int TS_STATE_POS = 20;

    // State Table Offset，重启时，必须纠正
    private final AtomicLong tranStateTableOffset = new AtomicLong(0);

    // 定时回查线程
    private final Timer timer = new Timer("CheckTransactionMessageTimer", true);


    public TransactionStateService(final DefaultMessageStore defaultMessageStore) {
        this.defaultMessageStore = defaultMessageStore;
        this.tranStateTable =
                new MapedFileQueue(defaultMessageStore.getMessageStoreConfig().getTranStateTableStorePath(),
                    defaultMessageStore.getMessageStoreConfig().getTranStateTableMapedFileSize(), null);

        this.tranRedoLog = new ConsumeQueue(//
            TRANSACTION_REDOLOG_TOPIC,//
            TRANSACTION_REDOLOG_TOPIC_QUEUEID,//
            defaultMessageStore.getMessageStoreConfig().getTranRedoLogStorePath(),//
            defaultMessageStore.getMessageStoreConfig().getTranRedoLogMapedFileSize(),//
            defaultMessageStore);
    }


    public boolean load() {
        boolean result = this.tranRedoLog.load();
        result = result && this.tranStateTable.load();

        return result;
    }


    private void initTimerTask() {
        final List<MapedFile> mapedFiles = this.tranStateTable.getMapedFiles();
        for (MapedFile mf : mapedFiles) {
            this.addTimerTask(mf);
        }
    }


    public void start() {
        this.initTimerTask();
    }


    public void shutdown() {
    }


    public int deleteExpiredStateFile(long offset) {
        int cnt = this.tranStateTable.deleteExpiredFileByOffset(offset, TSStoreUnitSize);
        return cnt;
    }


    public void recoverStateTable(final boolean lastExitOK) {
        if (lastExitOK) {
            this.recoverStateTableNormal();
        }
        else {
            // 第一步，删除State Table
            this.tranStateTable.destroy();
            // 第二步，通过RedoLog全量恢复StateTable
            this.recreateStateTable();
        }
    }


    private void recreateStateTable() {
        this.tranStateTable =
                new MapedFileQueue(defaultMessageStore.getMessageStoreConfig().getTranStateTableStorePath(),
                    defaultMessageStore.getMessageStoreConfig().getTranStateTableMapedFileSize(), null);

        final TreeSet<Long> preparedItemSet = new TreeSet<Long>();

        // 第一步，重头扫描RedoLog
        final long minOffset = this.tranRedoLog.getMinOffsetInQuque();
        long processOffset = minOffset;
        while (true) {
            SelectMapedBufferResult bufferConsumeQueue = this.tranRedoLog.getIndexBuffer(processOffset);
            if (bufferConsumeQueue != null) {
                try {
                    long i = 0;
                    for (; i < bufferConsumeQueue.getSize(); i += ConsumeQueue.CQStoreUnitSize) {
                        long offsetMsg = bufferConsumeQueue.getByteBuffer().getLong();
                        int sizeMsg = bufferConsumeQueue.getByteBuffer().getInt();
                        long tagsCode = bufferConsumeQueue.getByteBuffer().getLong();

                        // Prepared
                        if (TransactionStateService.PreparedMessageTagsCode == tagsCode) {
                            preparedItemSet.add(offsetMsg);
                        }
                        // Commit/Rollback
                        else {
                            preparedItemSet.remove(tagsCode);
                        }
                    }

                    processOffset += i;
                }
                finally {
                    // 必须释放资源
                    bufferConsumeQueue.release();
                }
            }
            else {
                break;
            }
        }

        log.info("scan transaction redolog over, End offset: {},  Prepared Transaction Count: {}", processOffset,
            preparedItemSet.size());
        // 第二步，重建StateTable
        Iterator<Long> it = preparedItemSet.iterator();
        while (it.hasNext()) {
            Long offset = it.next();
            MessageExt msgExt = this.defaultMessageStore.lookMessageByOffset(offset);
            if (msgExt != null) {
                this.appendPreparedTransaction(msgExt.getCommitLogOffset(), msgExt.getStoreSize(), (int) (msgExt
                    .getStoreTimestamp() / 1000), msgExt.getProperty(Message.PROPERTY_PRODUCER_GROUP).hashCode());
                this.tranStateTableOffset.incrementAndGet();
            }
        }
    }


    private void recoverStateTableNormal() {
        final List<MapedFile> mapedFiles = this.tranStateTable.getMapedFiles();
        if (!mapedFiles.isEmpty()) {
            // 从倒数第三个文件开始恢复
            int index = mapedFiles.size() - 3;
            if (index < 0)
                index = 0;

            int mapedFileSizeLogics = this.tranStateTable.getMapedFileSize();
            MapedFile mapedFile = mapedFiles.get(index);
            ByteBuffer byteBuffer = mapedFile.sliceByteBuffer();
            long processOffset = mapedFile.getFileFromOffset();
            long mapedFileOffset = 0;
            while (true) {
                for (int i = 0; i < mapedFileSizeLogics; i += TSStoreUnitSize) {

                    final long clOffset_read = byteBuffer.getLong();
                    final int size_read = byteBuffer.getInt();
                    final int timestamp_read = byteBuffer.getInt();
                    final int groupHashCode_read = byteBuffer.getInt();
                    final int state_read = byteBuffer.getInt();

                    boolean stateOK = false;
                    switch (state_read) {
                    case MessageSysFlag.TransactionPreparedType:
                    case MessageSysFlag.TransactionCommitType:
                    case MessageSysFlag.TransactionRollbackType:
                        stateOK = true;
                        break;
                    default:
                        break;
                    }

                    // 说明当前存储单元有效
                    // TODO 这样判断有效是否合理？
                    if (clOffset_read >= 0 && size_read > 0 && stateOK) {
                        mapedFileOffset = i + TSStoreUnitSize;
                    }
                    else {
                        log.info("recover current transaction state table file over,  " + mapedFile.getFileName()
                                + " " + clOffset_read + " " + size_read + " " + timestamp_read);
                        break;
                    }
                }

                // 走到文件末尾，切换至下一个文件
                if (mapedFileOffset == mapedFileSizeLogics) {
                    index++;
                    if (index >= mapedFiles.size()) {
                        // 当前条件分支不可能发生
                        log.info("recover last transaction state table file over, last maped file "
                                + mapedFile.getFileName());
                        break;
                    }
                    else {
                        mapedFile = mapedFiles.get(index);
                        byteBuffer = mapedFile.sliceByteBuffer();
                        processOffset = mapedFile.getFileFromOffset();
                        mapedFileOffset = 0;
                        log.info("recover next transaction state table file, " + mapedFile.getFileName());
                    }
                }
                else {
                    log.info("recover current transaction state table queue over " + mapedFile.getFileName() + " "
                            + (processOffset + mapedFileOffset));
                    break;
                }
            }

            processOffset += mapedFileOffset;
            this.tranStateTable.truncateDirtyFiles(processOffset);
            this.tranStateTableOffset.set(this.tranStateTable.getMaxOffset() / TSStoreUnitSize);
            log.info("recover normal over, transaction state table max offset: {}",
                this.tranStateTableOffset.get());
        }
    }

    private static final Logger tranlog = LoggerFactory.getLogger(MixAll.TransactionLoggerName);


    private void addTimerTask(final MapedFile mf) {
        this.timer.scheduleAtFixedRate(new TimerTask() {
            private final MapedFile mapedFile = mf;
            private final TransactionCheckExecuter transactionCheckExecuter =
                    TransactionStateService.this.defaultMessageStore.getTransactionCheckExecuter();
            private final long checkTransactionMessageAtleastInterval =
                    TransactionStateService.this.defaultMessageStore.getMessageStoreConfig()
                        .getCheckTransactionMessageAtleastInterval();


            private long getTranStateOffset(final long currentIndex) {
                long offset =
                        (this.mapedFile.getFileFromOffset() + currentIndex)
                                / TransactionStateService.TSStoreUnitSize;
                return offset;
            }


            @Override
            public void run() {
                try {
                    SelectMapedBufferResult selectMapedBufferResult = mapedFile.selectMapedBuffer(0);
                    if (selectMapedBufferResult != null) {
                        long preparedMessageCountInThisMapedFile = 0;
                        int i = 0;
                        try {

                            for (; i < selectMapedBufferResult.getSize(); i += TSStoreUnitSize) {
                                selectMapedBufferResult.getByteBuffer().position(i);

                                // Commit Log Offset
                                long clOffset = selectMapedBufferResult.getByteBuffer().getLong();
                                // Message Size
                                int msgSize = selectMapedBufferResult.getByteBuffer().getInt();
                                // Timestamp
                                int timestamp = selectMapedBufferResult.getByteBuffer().getInt();
                                // Producer Group Hashcode
                                int groupHashCode = selectMapedBufferResult.getByteBuffer().getInt();
                                // Transaction State
                                int tranType = selectMapedBufferResult.getByteBuffer().getInt();

                                // 已经提交或者回滚的消息跳过
                                if (tranType != MessageSysFlag.TransactionPreparedType) {
                                    continue;
                                }

                                // 遇到时间不符合，终止
                                long timestampLong = timestamp * 1000;
                                long diff = System.currentTimeMillis() - timestampLong;
                                if (diff < checkTransactionMessageAtleastInterval) {
                                    break;
                                }

                                preparedMessageCountInThisMapedFile++;

                                try {
                                    this.transactionCheckExecuter.gotoCheck(//
                                        groupHashCode,//
                                        getTranStateOffset(i),//
                                        clOffset,//
                                        msgSize);
                                }
                                catch (Exception e) {
                                    tranlog.warn("gotoCheck Exception", e);
                                }
                            }

                            // 无Prepared消息，且遍历完，则终止定时任务
                            if (0 == preparedMessageCountInThisMapedFile //
                                    && i == mapedFile.getFileSize()) {
                                tranlog
                                    .info(
                                        "remove the transaction timer task, because no prepared message in this mapedfile[{}]",
                                        mapedFile.getFileName());
                                this.cancel();
                            }
                        }
                        finally {
                            selectMapedBufferResult.release();
                        }

                        tranlog
                            .info(
                                "the transaction timer task execute over in this period, {} Prepared Message: {} Check Progress: {}/{}",
                                mapedFile.getFileName(),//
                                preparedMessageCountInThisMapedFile,//
                                i / TSStoreUnitSize,//
                                mapedFile.getFileSize() / TSStoreUnitSize//
                            );
                    }
                    else if (mapedFile.isFull()) {
                        tranlog.info("the mapedfile[{}] maybe deleted, cancel check transaction timer task",
                            mapedFile.getFileName());
                        this.cancel();
                        return;
                    }
                }
                catch (Exception e) {
                    log.error("check transaction timer task Exception", e);
                }
            }
        }, 1000 * 60, this.defaultMessageStore.getMessageStoreConfig().getCheckTransactionMessageTimerInterval());
    }


    /**
     * 单线程调用
     */
    public boolean appendPreparedTransaction(//
            final long clOffset,//
            final int size,//
            final int timestamp,//
            final int groupHashCode//
    ) {
        MapedFile mapedFile = this.tranStateTable.getLastMapedFile();
        if (null == mapedFile) {
            log.error("appendPreparedTransaction: create mapedfile error.");
            return false;
        }

        // 首次创建，加入定时任务中
        if (0 == mapedFile.getWrotePostion()) {
            this.addTimerTask(mapedFile);
        }

        this.byteBufferAppend.position(0);
        this.byteBufferAppend.limit(TSStoreUnitSize);

        // Commit Log Offset
        this.byteBufferAppend.putLong(clOffset);
        // Message Size
        this.byteBufferAppend.putInt(size);
        // Timestamp
        this.byteBufferAppend.putInt(timestamp);
        // Producer Group Hashcode
        this.byteBufferAppend.putInt(groupHashCode);
        // Transaction State
        this.byteBufferAppend.putInt(MessageSysFlag.TransactionPreparedType);

        return mapedFile.appendMessage(this.byteBufferAppend.array());
    }


    /**
     * 单线程调用
     */
    public boolean updateTransactionState(//
            final long tsOffset,//
            final long clOffset,//
            final int groupHashCode,//
            final int state//
    ) {
        SelectMapedBufferResult selectMapedBufferResult = this.findTransactionBuffer(tsOffset);
        if (selectMapedBufferResult != null) {
            try {
                final long clOffset_read = selectMapedBufferResult.getByteBuffer().getLong();
                final int size_read = selectMapedBufferResult.getByteBuffer().getInt();
                final int timestamp_read = selectMapedBufferResult.getByteBuffer().getInt();
                final int groupHashCode_read = selectMapedBufferResult.getByteBuffer().getInt();
                final int state_read = selectMapedBufferResult.getByteBuffer().getInt();

                // 校验数据正确性
                if (clOffset != clOffset_read) {
                    log.error("updateTransactionState error clOffset: {} clOffset_read: {}", clOffset,
                        clOffset_read);
                    return false;
                }

                // 校验数据正确性
                if (groupHashCode != groupHashCode_read) {
                    log.error("updateTransactionState error groupHashCode: {} groupHashCode_read: {}",
                        groupHashCode, groupHashCode_read);
                    return false;
                }

                // 判断是否已经更新过
                if (MessageSysFlag.TransactionPreparedType != state_read) {
                    log.warn("updateTransactionState error, the transaction is updated before.");
                    return true;
                }

                // 更新事务状态
                selectMapedBufferResult.getByteBuffer().putInt(TS_STATE_POS, state);
            }
            catch (Exception e) {
                log.error("updateTransactionState exception", e);
            }
            finally {
                selectMapedBufferResult.release();
            }
        }

        return false;
    }


    private SelectMapedBufferResult findTransactionBuffer(final long tsOffset) {
        final int mapedFileSize =
                this.defaultMessageStore.getMessageStoreConfig().getTranStateTableMapedFileSize();
        final long offset = tsOffset * TSStoreUnitSize;
        MapedFile mapedFile = this.tranStateTable.findMapedFileByOffset(offset);
        if (mapedFile != null) {
            SelectMapedBufferResult result = mapedFile.selectMapedBuffer((int) (offset % mapedFileSize));
            return result;
        }

        return null;
    }


    public AtomicLong getTranStateTableOffset() {
        return tranStateTableOffset;
    }


    public ConsumeQueue getTranRedoLog() {
        return tranRedoLog;
    }
}

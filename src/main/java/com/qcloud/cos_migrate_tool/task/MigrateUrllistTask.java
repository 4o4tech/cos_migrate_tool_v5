package com.qcloud.cos_migrate_tool.task;

import java.io.File;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

import com.qcloud.cos.transfer.TransferManager;
import com.qcloud.cos_migrate_tool.config.CopyFromUrllistConfig;
import com.qcloud.cos_migrate_tool.config.MigrateType;
import com.qcloud.cos_migrate_tool.meta.TaskStatics;
import com.qcloud.cos_migrate_tool.record.MigrateUrllistRecordElement;
import com.qcloud.cos_migrate_tool.record.RecordDb;
import com.qcloud.cos_migrate_tool.utils.Downloader;
import com.qcloud.cos_migrate_tool.utils.HeadAttr;

public class MigrateUrllistTask extends Task {

    private String url;
    private String srcKey;

    public MigrateUrllistTask(CopyFromUrllistConfig config, String url, String srcKey,
            TransferManager smallFileTransfer, TransferManager bigFileTransfer, RecordDb recordDb,
            Semaphore semaphore) {
        super(semaphore, config, smallFileTransfer, bigFileTransfer, recordDb);
        this.url = url;
        this.srcKey = srcKey;
        if (srcKey.startsWith("/")) {
            this.srcKey = srcKey.substring(1);
        }
    }

    private String buildCOSPath() {
        String cosPrefix = config.getCosPath();
        String cosPath = cosPrefix + "/" + srcKey; 
		cosPath = cosPath.replaceAll("/{2,}", "/");
		return cosPath;
    }

    @Override
    public void doTask() {

        String cosPath = buildCOSPath();
        String localPath = config.getTempFolderPath() + ThreadLocalRandom.current().nextLong(Long.MAX_VALUE) + "_" + Thread.currentThread().getName();
        HeadAttr headAttr = null;
        try {
            headAttr = Downloader.instance.headFile(url);
        } catch (Exception e) {
            String printMsg = String.format("head url attr fail, url: %s", url);
            System.err.println(printMsg);
            log.error(printMsg, e);
            TaskStatics.instance.addFailCnt();
            return;
        }
        
        if (headAttr == null) {
            String printMsg = String.format("head url attr fail, url: %s", url);
            System.err.println(printMsg);
            log.error(printMsg);
            TaskStatics.instance.addFailCnt();
            return;
        }

        MigrateUrllistRecordElement urllistRecordElement = new MigrateUrllistRecordElement(
                MigrateType.MIGRATE_FROM_URLLIST, config.getBucketName(), cosPath, url, headAttr);
        if (isExist(urllistRecordElement)) {
            TaskStatics.instance.addSkipCnt();
            return;
        }

        File localFile = new File(localPath);
         
        try {
            headAttr = Downloader.instance.downFile(url, localFile);
        } catch (Exception e) {
            String printMsg =
                    String.format("[fail] task_info: %s", urllistRecordElement.buildKey());
            System.err.println(printMsg);
            TaskStatics.instance.addFailCnt();
            log.error("download fail task_info: %s, [msg:{}]", url, e.getMessage());
            localFile.delete();
            return;
        }

        if (headAttr == null) {
            String printMsg =
                    String.format("[fail] task_info: %s", urllistRecordElement.buildKey());
            System.err.println(printMsg);
            log.error(printMsg);
            TaskStatics.instance.addFailCnt();
            localFile.deleteOnExit();
            return;
        }

        // upload
        if (!localFile.exists()) {
            String errMsg = String.format("[fail] taskInfo: %s. tmpfile: %s not exist",
                    urllistRecordElement.buildKey(), localPath);
            System.err.println(errMsg);
            log.error(errMsg);
            TaskStatics.instance.addFailCnt();
            return;
        }

        try {
            com.qcloud.cos.model.ObjectMetadata cosMetadata = new com.qcloud.cos.model.ObjectMetadata();
            cosMetadata.setUserMetadata(headAttr.userMetaMap);
            String requestId = uploadFile(config.getBucketName(), cosPath, localFile, config.getStorageClass(),
                    config.isEntireFileMd5Attached(), cosMetadata);
            saveRecord(urllistRecordElement);
            saveRequestId(cosPath, requestId);
            if (this.query_result == RecordDb.QUERY_RESULT.KEY_NOT_EXIST) {
                TaskStatics.instance.addSuccessCnt();
            } else {
                TaskStatics.instance.addUpdateCnt();
            }
            String printMsg = String.format("[ok] [requestid: %s], task_info: %s", requestId == null ? "NULL" : requestId, urllistRecordElement.buildKey());
            System.out.println(printMsg);
            log.info(printMsg);
        } catch (Exception e) {
            String printMsg =
                    String.format("[fail] task_info: %s", urllistRecordElement.buildKey());
            System.err.println(printMsg);
            log.error("[fail] task_info: {}, exception: {}", urllistRecordElement.buildKey(),
                    e.toString());
            TaskStatics.instance.addFailCnt();
        } finally {
            localFile.delete();
        }

    }
}

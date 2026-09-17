package com.xiaohua.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 差分计算：拿当前知识库的来源和索引清单比一次，算出「哪些要重灌、哪些不用动」。
 *
 * <p>纯函数、无 IO 无 Spring，所以增量更新的判断逻辑可以完全离线单测 ——
 * 这段逻辑判错一次的后果是「该重灌的没重灌（索引里是旧内容）」或者
 * 「不该删的被删了（索引里少内容）」，值得单独测清楚。</p>
 */
final class IndexDiff {

    private IndexDiff() {
    }

    /**
     * 差分结果。
     *
     * @param added        新增来源（清单里没有）
     * @param changed      内容变了的来源
     * @param removed      已被移除的来源（<b>不含</b>加载失败的来源）
     * @param unchanged    指纹一致的来源数
     * @param failed       失败来源里「清单中本来就有」的数量（索引里保留的是它们的旧内容）
     * @param fullRequired 是否必须走全量重建（清单缺失/不属于当前集合/切分参数变了）
     * @param reason       {@code fullRequired} 的原因，也用于日志
     */
    record Result(List<IndexManifest.SourceKey> added,
                  List<IndexManifest.SourceKey> changed,
                  List<IndexManifest.SourceKey> removed,
                  int unchanged,
                  int failed,
                  boolean fullRequired,
                  String reason) {

        /** 有没有需要写向量库的变化 */
        boolean hasChanges() {
            return !added.isEmpty() || !changed.isEmpty() || !removed.isEmpty();
        }

        /** 需要重新 embedding 的来源数（新增 + 变更） */
        int reembedCount() {
            return added.size() + changed.size();
        }
    }

    /**
     * 比对清单与当前来源。
     *
     * <p>三种情况直接判「不能增量、必须全量」：没有清单、清单属于另一个集合、切分参数变了。
     * 前两种是「不知道索引里到底是什么」，最后一种是「指纹判断不了切分边界的变化」—— 宁可多花 2 分钟重建，
     * 也不要留一个内容对不上的索引。</p>
     *
     * <p><b>失败来源不算删除</b>：抓取失败（网络抖了、页面下线中）时来源只是暂时加载不出来，
     * 这时若按「清单里有、当前没有」判成删除，就会把索引里的正文删掉 —— 这是整个增量更新里最危险的一步。
     * 所以 {@code failed} 里的来源既不算删除、也不算未变，清单条目原样保留，下次重建重试。</p>
     *
     * @param prev       上次的清单；{@code null} 表示还没有清单
     * @param current    当前加载成功的来源 → 指纹/片段数
     * @param failed     加载失败的来源（网页抓取异常、正文为空等）
     */
    static Result compare(IndexManifest prev,
                          String collectionName,
                          int chunkSize,
                          int overlap,
                          Map<IndexManifest.SourceKey, IndexManifest.SourceEntry> current,
                          Set<IndexManifest.SourceKey> failed) {

        if (prev == null || prev.collectionName() == null || prev.collectionName().isBlank()) {
            return fullRebuild("缺少来源清单（首次使用增量更新，或清单被删了）");
        }
        if (!prev.collectionName().equals(collectionName)) {
            return fullRebuild("来源清单属于集合 " + prev.collectionName() + "，当前集合是 " + collectionName);
        }
        if (prev.chunkSize() != chunkSize || prev.overlap() != overlap) {
            return fullRebuild("切分参数已变（" + prev.chunkSize() + "/" + prev.overlap()
                    + " → " + chunkSize + "/" + overlap + "）");
        }

        List<IndexManifest.SourceKey> added = new ArrayList<>();
        List<IndexManifest.SourceKey> changed = new ArrayList<>();
        int unchanged = 0;
        for (Map.Entry<IndexManifest.SourceKey, IndexManifest.SourceEntry> entry : current.entrySet()) {
            IndexManifest.SourceEntry known = prev.sources().get(entry.getKey());
            if (known == null) {
                added.add(entry.getKey());
            } else if (!known.fingerprint().equals(entry.getValue().fingerprint())) {
                changed.add(entry.getKey());
            } else {
                unchanged++;
            }
        }

        // 已被移除的来源：清单里有、当前没有、并且不是「加载失败」。顺序沿用清单顺序，日志读起来稳定
        List<IndexManifest.SourceKey> removed = new ArrayList<>();
        int failedKnown = 0;
        for (IndexManifest.SourceKey key : prev.sources().keySet()) {
            if (current.containsKey(key)) {
                continue;
            }
            if (failed.contains(key)) {
                failedKnown++;
            } else {
                removed.add(key);
            }
        }

        return new Result(added, changed, removed, unchanged, failedKnown, false, null);
    }

    private static Result fullRebuild(String reason) {
        return new Result(List.of(), List.of(), List.of(), 0, 0, true, reason);
    }
}

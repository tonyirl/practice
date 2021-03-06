package me.tony.practice.common.weight;


import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static me.tony.practice.common.weight.TrafficRule.exclusive;
import static me.tony.practice.common.weight.TrafficRule.nonexclusive;

public class WeightedSelector {

    Map<String, NavigableMap<Double, List<String>>> configMap = Collections.emptyMap();
    TrafficRuleService service = new TrafficRuleService();
    Random random = new Random();

    public static void main(String[] args) {
        List<TrafficRule> data = Arrays.asList(
                exclusive("1.1.z.1", "a", 0.1),
                exclusive("1.1.z.1", "b", 0.15),
                nonexclusive("1.1.z.1", "c"),
                nonexclusive("1.1.z.1", "d"),
                nonexclusive("1.1.z.1", "e"),
                nonexclusive("1.1.z.1", "f"),
                exclusive("1.1.z.1", "g", 0.28)
        );
        WeightedSelector selector = new WeightedSelector();
        selector.refresh(data);
        int total = 100000000;
        int aNum, bNum, gNum, bidNum;
        aNum = bNum = gNum = bidNum = 0;
        long start = System.currentTimeMillis();
        for (int i = 0; i < total; i++) {
            List<String> target = selector.selectDsp("1.1.z.1");
            if (target.contains("a")) {
                aNum++;
            } else if (target.contains("b")) {
                bNum++;
            } else if (target.contains("g")) {
                gNum++;
            } else {
                bidNum++;
            }
        }
        System.out.println(System.currentTimeMillis() - start);
        System.out.println(aNum);
        System.out.println(bNum);
        System.out.println(gNum);
        System.out.println(bidNum);
    }

    void refresh(List<TrafficRule> data) {
        Map<String, List<Pair<Double, List<String>>>> dataMap = this.service.service(data);
        if (dataMap.isEmpty()) {
            return;
        }
        Map<String, NavigableMap<Double, List<String>>> tmpConfigMap = dataMap.entrySet().parallelStream()
                .collect(Collectors.toConcurrentMap(Map.Entry::getKey, entry -> {
                    final NavigableMap<Double, List<String>> weightedMap = new TreeMap<>();
                    // never never use parallelStream instead
                    // for weightedMap is changed in each step
                    // and will effect next step
                    // so call side effect
                    entry.getValue().forEach(pair -> {
                        double lastWeight = weightedMap.isEmpty() ? 0 : weightedMap.lastKey();
                        weightedMap.put(pair.getLeft() + lastWeight, pair.getRight());
                    });
                    return Collections.unmodifiableNavigableMap(weightedMap);
                }));
        if (!tmpConfigMap.isEmpty()) {
            configMap = tmpConfigMap;
        }
    }

    List<String> selectDsp(String tagId) {
        if (!configMap.containsKey(tagId)) {
            return Collections.emptyList();
        }
        NavigableMap<Double, List<String>> weightedConfig = configMap.get(tagId);
        double randomWeight = weightedConfig.lastKey() * random.nextDouble();
        NavigableMap<Double, List<String>> tailMap = weightedConfig.tailMap(randomWeight, false);
        return weightedConfig.get(tailMap.firstKey());
    }
}

class TrafficRuleService {
    Map<String, List<Pair<Double, List<String>>>> service(List<TrafficRule> data) {
        Map<String, List<TrafficRule>> mid = data.parallelStream().collect(Collectors.groupingByConcurrent(tr -> tr.tagId));
        final Map<String, List<Pair<Double, List<String>>>> ret = new ConcurrentHashMap<>();
        mid.entrySet().parallelStream().forEach(entry -> {
            String tagId = entry.getKey();
            if (!ret.containsKey(tagId)) {
                ret.put(tagId, new ArrayList<>());
            }
            List<Pair<Double, List<String>>> weightedTargets = ret.get(tagId);
            List<String> nonExclusiveTargets = new ArrayList<>();

            double totalExclusiveRate = 0.0;
            for (TrafficRule tr : entry.getValue()) {
                if (tr.exclusive) {
                    Pair<Double, List<String>> exclusiveOne = Pair.of(tr.weight, Collections.singletonList(tr.dspKey));
                    weightedTargets.add(exclusiveOne);
                    totalExclusiveRate += tr.weight;
                } else {
                    nonExclusiveTargets.add(tr.dspKey);
                }
            }
            if (totalExclusiveRate < 1 && !nonExclusiveTargets.isEmpty()) {
                weightedTargets.add(Pair.of(1 - totalExclusiveRate, nonExclusiveTargets));
            }
        });
        return ret;
    }
}

class TrafficRule {
    String tagId;
    String dspKey;
    Boolean exclusive;
    Double weight;

    static TrafficRule exclusive(String tagId, String dspKey, double weight) {
        TrafficRule ret = new TrafficRule();
        ret.tagId = tagId;
        ret.dspKey = dspKey;
        ret.exclusive = true;
        ret.weight = weight;
        return ret;
    }

    static TrafficRule nonexclusive(String tagId, String dspKey) {
        TrafficRule ret = new TrafficRule();
        ret.tagId = tagId;
        ret.dspKey = dspKey;
        ret.exclusive = false;
        ret.weight = 0.0;
        return ret;
    }
}

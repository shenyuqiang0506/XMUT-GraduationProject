package org.shen.ai.domain;

import java.time.*;
import java.util.*;
import java.util.regex.*;

import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

/**
 * 模型只提交增量字段及原文证据，服务器决定是否接受。
 * 时间与人数不采用模型猜测值；相对日期以本次服务器上海日期解释，含糊的“下午”没有默认时刻。
 * 条件未被本条消息明确变更时沿用原值，未知校区/设备会持续阻止查询。
 */
@Component
@Profile("business")
public class ConditionNormalizer {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String NUM = "[0-9零〇一二两三四五六七八九十百千万]+";
    private static final Set<String> FIELDS = Set.of("campus", "date", "startLocal", "endLocal", "durationMinutes", "participantCount", "requiredFeatures", "equipmentRequirements", "preferredBuilding");
    private static final Map<String, String> FEATURES = Map.of("PROJECTOR", "投影", "WHITEBOARD", "白板", "SOUND_SYSTEM", "音响", "MICROPHONE", "话筒", "DESK", "桌", "NETWORK", "网络", "COMPUTER", "电脑");

    public record Normalized(Map<String, Object> conditions, List<String> missingFields) {
    }

    public Normalized apply(Map<String, Object> current, AiCommands.Extraction patch, String text, LocalDate today, Map<String, Object> options) {
        if (patch == null || patch.changes() == null || patch.evidence() == null || !FIELDS.containsAll(patch.changes().keySet())
                || !patch.changes().keySet().equals(patch.evidence().keySet()))
            throw new AiFailure("AI_INVALID_OUTPUT");
        var next = new LinkedHashMap<>(current);
        next.putIfAbsent("requiredFeatures", List.of());
        next.putIfAbsent("equipmentRequirements", List.of());
        for (var change : patch.changes().entrySet()) {
            String field = change.getKey(), proof = patch.evidence().get(field);
            if (proof == null || proof.isBlank() || proof.length() > 300 || !text.contains(proof))
                throw new AiFailure("AI_UNGROUNDED_CONDITION");
            try {
                switch (field) {
                    case "date" -> next.put(field, date(proof, today).toString());
                    case "startLocal", "endLocal" -> next.put(field, time(proof).toString());
                    case "durationMinutes" -> next.put(field, duration(proof));
                    case "participantCount" -> next.put(field, participants(proof));
                    case "campus", "preferredBuilding" -> {
                        if (change.getValue() == null && clear(proof)) next.remove(field);
                        else {
                            if (!(change.getValue() instanceof String value) || value.isBlank() || value.length() > 100 || !compact(proof).contains(compact(value)))
                                throw new AiFailure("AI_UNGROUNDED_CONDITION");
                            next.put(field, value);
                        }
                    }
                    case "requiredFeatures" -> next.put(field, features(next.get(field), change.getValue(), proof));
                    case "equipmentRequirements" ->
                            next.put(field, equipmentChange(next.get(field), change.getValue(), proof, options));
                    default -> throw new AiFailure("AI_INVALID_OUTPUT");
                }
            } catch (AiFailure failure) {
                throw failure;
            } catch (RuntimeException incomplete) {
                next.remove(field);
            }
        }
        // 用户明确改动时间后不沿用旧结束时刻推算新的时长，优先采用用户本次选择的表达方式。
        if (patch.changes().containsKey("durationMinutes")) next.remove("endLocal");
        else if (patch.changes().containsKey("endLocal")) next.remove("durationMinutes");
        next.remove("startAt");
        next.remove("endAt");
        return validate(next, today, options);
    }

    public Normalized validate(Map<String, Object> current, LocalDate today, Map<String, Object> options) {
        var next = new LinkedHashMap<>(current);
        var missing = new LinkedHashSet<String>();
        if (!next.containsKey("date")) missing.add("date");
        if (!next.containsKey("startLocal")) missing.add("startLocal");
        if (!next.containsKey("endLocal") && !next.containsKey("durationMinutes")) missing.add("endLocalOrDuration");
        Object count = next.get("participantCount");
        if (!(count instanceof Number n) || n.longValue() < 1 || n.longValue() > 100000)
            missing.add("participantCount");
        if (next.get("campus") != null && !strings(options.get("campuses"), 200).contains(next.get("campus")))
            missing.add("campus");
        var known = strings(options.get("equipmentTypes"), 200);
        if (next.get("equipmentRequirements") instanceof List<?> needs) {
            int total = 0;
            Set<String> seen = new HashSet<>();
            for (Object value : needs) {
                if (!(value instanceof Map<?, ?> need) || !(need.get("type") instanceof String type) || !known.contains(type) || !seen.add(type)
                        || !(need.get("count") instanceof Number amount) || amount.intValue() < 1 || amount.intValue() > 20) {
                    missing.add("equipmentRequirements");
                    continue;
                }
                total += amount.intValue();
            }
            if (total > 40 || needs.size() > 10) missing.add("equipmentRequirements");
        } else if (next.containsKey("equipmentRequirements")) missing.add("equipmentRequirements");
        if (next.containsKey("date") && next.containsKey("startLocal") && (next.containsKey("endLocal") || next.containsKey("durationMinutes"))) {
            try {
                LocalDate day = LocalDate.parse(next.get("date").toString());
                LocalTime clock = LocalTime.parse(next.get("startLocal").toString());
                var start = day.atTime(clock).atZone(ZONE);
                var end = next.containsKey("endLocal") ? day.atTime(LocalTime.parse(next.get("endLocal").toString())).atZone(ZONE)
                        : start.plusMinutes(((Number) next.get("durationMinutes")).longValue());
                if (day.isBefore(today) || !end.isAfter(start) || !start.toLocalDate().equals(end.toLocalDate()))
                    throw new IllegalArgumentException();
                next.put("startAt", start.toOffsetDateTime().toString());
                next.put("endAt", end.toOffsetDateTime().toString());
            } catch (RuntimeException invalid) {
                missing.add("timeRange");
            }
        }
        return new Normalized(next, List.copyOf(missing));
    }

    private static String compact(String value) {
        return value.replaceAll("\\s", "");
    }

    private static boolean clear(String proof) {
        return Pattern.compile("取消|不需要|无需|不用|去掉|不限").matcher(proof).find();
    }

    private static boolean addition(String proof) {
        return Pattern.compile("再加|增加|追加|加上").matcher(proof).find();
    }

    private static boolean replacement(String proof) {
        return Pattern.compile("只要|只需要|仅需|仅要|全部换成|全部改成|全部改为").matcher(proof).find();
    }

    private record ArrayChange(String operation, Object items) {
    }

    /**
     * 旧版数组仍可读取，但默认仅合并当前明确提到的项，局部取消不能清空整个字段。
     */
    private static ArrayChange arrayChange(Object raw, String proof) {
        // “不用再加”是否保留现有同类需求需要澄清，不能把否定句当作追加或删除证据。
        if (Pattern.compile("(?:不要|不用|无需|不必|不需要|不再|别)(?:再)?(?:增加|追加|加上|加)").matcher(compact(proof)).find()
                || clear(proof) && addition(proof) && !replacement(proof))
            throw new AiFailure("AI_UNGROUNDED_CONDITION");
        if (raw instanceof Map<?, ?> map) {
            if (!map.keySet().equals(Set.of("operation", "items")) || !(map.get("operation") instanceof String operation)
                    || !Set.of("MERGE", "ADD", "REMOVE", "REPLACE", "CLEAR").contains(operation) || !(map.get("items") instanceof List<?>))
                throw new AiFailure("AI_INVALID_OUTPUT");
            if ((operation.equals("REMOVE") || operation.equals("CLEAR")) && !clear(proof)
                    || operation.equals("REPLACE") && !replacement(proof) || operation.equals("ADD") && !addition(proof)
                    || operation.equals("MERGE") && (clear(proof) || replacement(proof) || addition(proof)))
                throw new AiFailure("AI_UNGROUNDED_CONDITION");
            return new ArrayChange(operation, map.get("items"));
        }
        if (!(raw instanceof List<?>)) throw new AiFailure("AI_INVALID_OUTPUT");
        return new ArrayChange(replacement(proof) ? "REPLACE" : clear(proof) ? "REMOVE" : addition(proof) ? "ADD" : "MERGE", raw);
    }

    private static boolean mentionedFeature(String feature, String proof) {
        return proof.contains(feature) || proof.contains(FEATURES.get(feature));
    }

    private static boolean globalClear(String proof, String field) {
        return clear(proof) && Pattern.compile("(?:全部|所有|任何).*" + field + "|" + field + ".*(?:全部|所有|要求|需求|不限)|(?:不需要|无需|不用)" + field).matcher(compact(proof)).find();
    }

    private static List<String> features(Object current, Object raw, String proof) {
        var change = arrayChange(raw, proof);
        var items = strings(change.items(), 7);
        if (!FEATURES.keySet().containsAll(items) || items.stream().distinct().count() != items.size())
            throw new AiFailure("AI_INVALID_OUTPUT");
        if (items.stream().anyMatch(f -> !mentionedFeature(f, proof))) throw new AiFailure("AI_UNGROUNDED_CONDITION");
        var mentioned = FEATURES.keySet().stream().filter(f -> mentionedFeature(f, proof)).toList();
        var next = new TreeSet<>(strings(current, 7));
        if (change.operation().equals("CLEAR") || change.operation().equals("REMOVE") && items.isEmpty() && mentioned.isEmpty()) {
            if (!items.isEmpty() || !mentioned.isEmpty() || !globalClear(proof, "设施"))
                throw new AiFailure("AI_UNGROUNDED_CONDITION");
            next.clear();
        } else if (change.operation().equals("REMOVE")) next.removeAll(items.isEmpty() ? mentioned : items);
        else {
            if (items.isEmpty()) throw new AiFailure("AI_UNGROUNDED_CONDITION");
            if (change.operation().equals("REPLACE")) next.clear();
            next.addAll(items);
        }
        return List.copyOf(next);
    }

    private static List<Map<String, Object>> equipmentChange(Object current, Object raw, String proof, Map<String, Object> options) {
        var change = arrayChange(raw, proof);
        var next = new TreeMap<String, Integer>();
        if (!(current instanceof List<?> old)) throw new AiFailure("AI_INVALID_OUTPUT");
        for (Object item : old) {
            if (!(item instanceof Map<?, ?> need) || !(need.get("type") instanceof String type) || !(need.get("count") instanceof Number count))
                throw new AiFailure("AI_INVALID_OUTPUT");
            next.put(type, count.intValue());
        }
        if (change.operation().equals("REMOVE") || change.operation().equals("CLEAR")) {
            var items = strings(change.items(), 10);
            var known = new TreeSet<>(next.keySet());
            known.addAll(strings(options.get("equipmentTypes"), 200));
            var mentioned = known.stream().filter(type -> compact(proof).contains(compact(type))).toList();
            if (items.stream().distinct().count() != items.size() || items.stream().anyMatch(type -> !mentioned.contains(type)))
                throw new AiFailure("AI_UNGROUNDED_CONDITION");
            if (change.operation().equals("CLEAR") || items.isEmpty() && mentioned.isEmpty()) {
                if (!items.isEmpty() || !mentioned.isEmpty() || !globalClear(proof, "设备"))
                    throw new AiFailure("AI_UNGROUNDED_CONDITION");
                next.clear();
            } else (items.isEmpty() ? mentioned : items).forEach(next::remove);
        } else {
            var items = equipment(change.items(), proof);
            if (items.isEmpty()) throw new AiFailure("AI_UNGROUNDED_CONDITION");
            if (change.operation().equals("REPLACE")) next.clear();
            for (var item : items) {
                String type = (String) item.get("type");
                int count = (Integer) item.get("count");
                next.put(type, change.operation().equals("ADD") ? Math.addExact(next.getOrDefault(type, 0), count) : count);
            }
        }
        return next.entrySet().stream().map(e -> Map.<String, Object>of("type", e.getKey(), "count", e.getValue())).toList();
    }

    private static List<String> strings(Object value, int limit) {
        if (!(value instanceof List<?> values) || values.size() > limit || values.stream().anyMatch(v -> !(v instanceof String)))
            throw new AiFailure("AI_INVALID_OUTPUT");
        return values.stream().map(Object::toString).toList();
    }

    private static List<Map<String, Object>> equipment(Object raw, String proof) {
        if (Pattern.compile("[0-9]\\.[0-9]").matcher(proof).find()) throw new AiFailure("AI_UNGROUNDED_CONDITION");
        if (!(raw instanceof List<?> values) || values.size() > 10) throw new AiFailure("AI_INVALID_OUTPUT");
        if (values.isEmpty() && !clear(proof)) throw new AiFailure("AI_UNGROUNDED_CONDITION");
        var needs = new ArrayList<Map<String, Object>>();
        var seen = new HashSet<String>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> need) || !need.keySet().equals(Set.of("type", "count")) || !(need.get("type") instanceof String type)
                    || type.isBlank() || type.length() > 32 || !(need.get("count") instanceof Number amount) || amount.doubleValue() != amount.intValue()
                    || amount.intValue() < 1 || amount.intValue() > 20 || !seen.add(type))
                throw new AiFailure("AI_INVALID_OUTPUT");
            String normalized = compact(proof), label = Pattern.quote(compact(type));
            var before = Pattern.compile("(" + NUM + ")(?:个|只|台|件|支|套)?" + label).matcher(normalized);
            var after = Pattern.compile(label + "(?:需要|要|共)?(" + NUM + ")(?:个|只|台|件|支|套)?").matcher(normalized);
            int count = before.find() ? number(before.group(1)) : after.find() ? number(after.group(1)) : -1;
            if (count != amount.intValue()) throw new AiFailure("AI_UNGROUNDED_CONDITION");
            needs.add(Map.of("type", type, "count", count));
        }
        return needs;
    }

    public static LocalDate date(String proof, LocalDate today) {
        var iso = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}").matcher(proof);
        if (iso.find()) return LocalDate.parse(iso.group());
        var chinese = Pattern.compile("([0-9]{4})年([0-9]{1,2})月([0-9]{1,2})日").matcher(proof);
        if (chinese.find())
            return LocalDate.of(Integer.parseInt(chinese.group(1)), Integer.parseInt(chinese.group(2)), Integer.parseInt(chinese.group(3)));
        if (proof.contains("后天")) return today.plusDays(2);
        if (proof.contains("明天")) return today.plusDays(1);
        if (proof.contains("今天")) return today;
        var week = Pattern.compile("(下|本|这)?(?:周|星期)([一二三四五六日天])").matcher(proof);
        if (week.find()) {
            int target = "一二三四五六日".indexOf(week.group(2).replace('天', '日')) + 1;
            LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
            return monday.plusDays(target - 1L + ("下".equals(week.group(1)) ? 7 : 0));
        }
        throw new IllegalArgumentException("需要明确日期");
    }

    public static LocalTime time(String proof) {
        if (Pattern.compile("[0-9]\\.[0-9]").matcher(proof).find())
            throw new IllegalArgumentException("请提供明确时刻");
        var digital = Pattern.compile("([0-9]{1,2}):([0-9]{2})").matcher(proof);
        if (digital.find()) {
            int hour = Integer.parseInt(digital.group(1)), minute = Integer.parseInt(digital.group(2));
            if (digital.find()) throw new IllegalArgumentException("需要单一明确时刻");
            return clock(hour, minute, proof);
        }
        var named = Pattern.compile("(" + NUM + ")[点时](半|(" + NUM + ")分)?").matcher(proof);
        if (named.find()) {
            int hour = number(named.group(1)), minute = "半".equals(named.group(2)) ? 30 : named.group(3) != null ? number(named.group(3)) : 0;
            if (named.find()) throw new IllegalArgumentException("需要单一明确时刻");
            return clock(hour, minute, proof);
        }
        throw new IllegalArgumentException("需要明确时刻");
    }

    private static LocalTime clock(int hour, int minute, String proof) {
        boolean pm = Pattern.compile("下午|晚上|中午").matcher(proof).find(), am = Pattern.compile("上午|早上|凌晨").matcher(proof).find();
        if (pm && am || am && hour > 12) throw new IllegalArgumentException("时段与时刻矛盾");
        if (pm && hour < 12) hour += 12;
        if (proof.contains("凌晨") && hour == 12) hour = 0;
        return LocalTime.of(hour, minute);
    }

    private static int participants(String proof) {
        if (Pattern.compile("负|-[0-9]|[0-9]\\.[0-9]|至少|最多|大约|左右|以上|以下|" + NUM + "(?:到|至|~|～)" + NUM).matcher(proof).find())
            throw new IllegalArgumentException("需要明确正整数人数");
        var match = Pattern.compile("(" + NUM + ")(?:个)?(?:人|位|名)").matcher(proof);
        if (match.find()) return positive(number(match.group(1)), 100000);
        var changed = Pattern.compile("人数(?:改成|变成|改为|为|是)?(" + NUM + ")").matcher(compact(proof));
        if (changed.find()) return positive(number(changed.group(1)), 100000);
        if (proof.matches(NUM)) return positive(number(proof), 100000);
        throw new IllegalArgumentException("需要明确人数");
    }

    private static int duration(String proof) {
        if (Pattern.compile("负|-[0-9]|[0-9]\\.[0-9]|" + NUM + "(?:到|至|~|～)" + NUM).matcher(proof).find())
            throw new IllegalArgumentException("需要明确正数时长");
        proof = compact(proof);
        var hours = Pattern.compile("(" + NUM + ")(?:个)?(半)?小时").matcher(proof);
        if (hours.find()) {
            int duration = Math.multiplyExact(number(hours.group(1)), 60);
            if (hours.group(2) != null || proof.substring(hours.end()).startsWith("半"))
                duration = Math.addExact(duration, 30);
            var extra = Pattern.compile("(" + NUM + ")分钟").matcher(proof.substring(hours.end()));
            if (extra.find()) duration = Math.addExact(duration, number(extra.group(1)));
            return positive(duration, 1440);
        }
        if (proof.contains("半小时")) return 30;
        var minutes = Pattern.compile("(" + NUM + ")分钟").matcher(proof);
        if (minutes.find()) return positive(number(minutes.group(1)), 1440);
        throw new IllegalArgumentException("需要明确时长");
    }

    private static int positive(int value, int max) {
        if (value < 1 || value > max) throw new IllegalArgumentException();
        return value;
    }

    private static int number(String text) {
        if (text.matches("[0-9]+")) return Integer.parseInt(text);
        int total = 0, section = 0, digit = 0;
        for (char c : text.toCharArray()) {
            int n = "零一二三四五六七八九".indexOf(c == '两' ? '二' : c == '〇' ? '零' : c);
            if (n >= 0) {
                digit = n;
                continue;
            }
            int unit = switch (c) {
                case '十' -> 10;
                case '百' -> 100;
                case '千' -> 1000;
                case '万' -> 10000;
                default -> throw new IllegalArgumentException();
            };
            if (unit == 10000) {
                section += digit;
                total += section * unit;
                section = 0;
            } else section += (digit == 0 ? 1 : digit) * unit;
            digit = 0;
        }
        return total + section + digit;
    }
}

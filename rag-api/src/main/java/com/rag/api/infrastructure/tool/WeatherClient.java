package com.rag.api.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * 天气查询（Open-Meteo，免密钥）：geocoding 定位 + 实时天气 / 未来多天预报。
 */
@Slf4j
@Component
public class WeatherClient {

    /** 预报最多查询天数（含今天）。 */
    public static final int MAX_FORECAST_DAYS = 7;

    private final WebClient webClient = WebClient.builder().build();

    /**
     * @param location 城市名
     * @param days     1=实时天气；2~7=未来多天预报（含今天）
     */
    public String query(String location, int days) {
        try {
            JsonNode geo = webClient.get()
                    .uri("https://geocoding-api.open-meteo.com/v1/search?name={name}&count=1&language=zh&format=json", location)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(10));
            JsonNode hit = geo == null ? null : geo.path("results").path(0);
            if (hit == null || hit.isMissingNode()) {
                return "未查询到地区：" + location;
            }
            double lat = hit.path("latitude").asDouble();
            double lon = hit.path("longitude").asDouble();
            String name = hit.path("name").asText(location);
            return days <= 1 ? current(name, lat, lon) : forecast(name, lat, lon, days);
        } catch (Exception e) {
            log.warn("天气查询失败: {}", e.getMessage());
            return "天气查询失败：" + e.getMessage();
        }
    }

    private String current(String name, double lat, double lon) {
        JsonNode weather = webClient.get()
                .uri("https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m&timezone=auto",
                        lat, lon)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(10));
        JsonNode cur = weather == null ? weather : weather.path("current");
        if (cur == null || cur.isMissingNode()) {
            return "天气查询失败：" + name;
        }
        return "%s 当前天气：气温 %s°C，湿度 %s%%，风速 %s km/h，天气状况：%s"
                .formatted(name,
                        cur.path("temperature_2m").asText(),
                        cur.path("relative_humidity_2m").asText(),
                        cur.path("wind_speed_10m").asText(),
                        describeCode(cur.path("weather_code").asInt()));
    }

    private String forecast(String name, double lat, double lon, int days) {
        int forecastDays = Math.min(Math.max(days, 2), MAX_FORECAST_DAYS);
        JsonNode weather = webClient.get()
                .uri("https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&forecast_days={n}&timezone=auto",
                        lat, lon, forecastDays)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(10));
        JsonNode daily = weather == null ? null : weather.path("daily");
        if (daily == null || daily.path("time").isMissingNode()) {
            return "天气预报查询失败：" + name;
        }
        StringBuilder sb = new StringBuilder(name).append(" 未来").append(forecastDays).append("天天气预报：\n");
        for (int i = 0; i < forecastDays; i++) {
            sb.append(daily.path("time").path(i).asText())
                    .append(" ").append(describeCode(daily.path("weather_code").path(i).asInt()))
                    .append("，最高 ").append(daily.path("temperature_2m_max").path(i).asText()).append("°C")
                    .append("，最低 ").append(daily.path("temperature_2m_min").path(i).asText()).append("°C")
                    .append("，降水概率 ").append(daily.path("precipitation_probability_max").path(i).asText()).append("%\n");
        }
        return sb.toString().stripTrailing();
    }

    private String describeCode(int code) {
        return switch (code) {
            case 0 -> "晴";
            case 1, 2 -> "多云";
            case 3 -> "阴";
            case 45, 48 -> "雾";
            case 51, 53, 55 -> "毛毛雨";
            case 56, 57 -> "冻毛毛雨";
            case 61, 63, 65 -> "雨";
            case 66, 67 -> "冻雨";
            case 71, 73, 75 -> "雪";
            case 77 -> "阵雪";
            case 80, 81, 82 -> "阵雨";
            case 85, 86 -> "阵雪";
            case 95 -> "雷暴";
            case 96, 99 -> "雷暴伴冰雹";
            default -> "天气代码 " + code;
        };
    }
}

package org.app;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.*;

import io.prometheus.client.Gauge;
import io.prometheus.client.exporter.HTTPServer;
import io.prometheus.client.hotspot.DefaultExports;

import java.io.IOException;
import java.util.Properties;

public class WeatherStreamApp {

    // ----------  PROMETHEUS METRICS ----------
    private static final Gauge temperatureGauge = Gauge.build()
            .name("weather_average_temperature")
            .help("Temp moyenne en Fahrenheit par station")
            .labelNames("station")
            .register();

    private static final Gauge humidityGauge = Gauge.build()
            .name("weather_average_humidity")
            .help("Humidité moyenne en pourcentage par station")
            .labelNames("station")
            .register();

    public static void main(String[] args) throws IOException {

        // ----------  KAFKA STREAMS CONFIG ----------
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "weather-streams-app");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

        StreamsBuilder builder = new StreamsBuilder();

        // ---------- 1. Read stream ----------
        KStream<String, String> weatherStream = builder.stream("weather-data");

        // ---------- 2. Filter temperature > 30 ----------
        KStream<String, String> highTempStream = weatherStream.filter((key, value) -> {
            try {
                String[] parts = value.split(",");
                double temp = Double.parseDouble(parts[1]);
                return temp > 30;
            } catch (Exception e) {
                return false; // skip invalid data
            }
        });

        // ---------- 3. Convert Celsius to Fahrenheit ----------
        KStream<String, String> fahrenheitStream = highTempStream.mapValues(value -> {
            String[] p = value.split(",");
            String station = p[0];
            double celsius = Double.parseDouble(p[1]);
            int humidity = Integer.parseInt(p[2]);

            double fahrenheit = (celsius * 9 / 5) + 32;
            return station + "," + fahrenheit + "," + humidity;
        });

        // ---------- 4. Group by station ----------
        KGroupedStream<String, String> grouped = fahrenheitStream
                .selectKey((key, value) -> value.split(",")[0])
                .groupByKey();

        // ---------- Aggregation ----------
        KTable<String, String> averages = grouped.aggregate(
                () -> "0,0,0",  // tempSum, humiditySum, count

                (station, value, aggregate) -> {
                    String[] v = value.split(",");
                    double temp = Double.parseDouble(v[1]);
                    double humidity = Double.parseDouble(v[2]);

                    String[] agg = aggregate.split(",");
                    double tempSum = Double.parseDouble(agg[0]) + temp;
                    double humiditySum = Double.parseDouble(agg[1]) + humidity;
                    double count = Double.parseDouble(agg[2]) + 1;

                    double avgTemp = tempSum / count;
                    double avgHumidity = humiditySum / count;

                    // Return new state
                    return avgTemp + "," + avgHumidity + "," + count;
                }
        );

        // ---------- 5. Write results + UPDATE PROMETHEUS ----------
        averages.toStream().mapValues((station, v) -> {
            String[] p = v.split(",");

            double avgTemp = Double.parseDouble(p[0]);
            double avgHumidity = Double.parseDouble(p[1]);

            // —— Update metrics
            temperatureGauge.labels(station).set(avgTemp);
            humidityGauge.labels(station).set(avgHumidity);

            return station + " : Température Moyenne = " + avgTemp +
                    "°F, Humidité Moyenne = " + avgHumidity + "%";
        }).to("station-average");

        // ---------- Build & Run ----------
        KafkaStreams streams = new KafkaStreams(builder.build(), props);

        // PROMETHEUS SERVER (http://localhost:1234/metrics)
        DefaultExports.initialize(); // JVM metrics
        new HTTPServer(1234);

        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));

        System.out.println("Kafka Streams météo démarré avec Prometheus !");

        streams.start();
    }
}

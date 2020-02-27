package io.vividcode.happyride.dispatcherservice.service;

import io.eventuate.common.json.mapper.JSonMapper;
import io.eventuate.tram.events.aggregates.ResultWithDomainEvents;
import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.messaging.producer.MessageBuilder;
import io.eventuate.tram.messaging.producer.MessageProducer;
import io.vividcode.happyride.common.Position;
import io.vividcode.happyride.dispatcherservice.api.events.DispatchDomainEvent;
import io.vividcode.happyride.dispatcherservice.api.events.DriverLocation;
import io.vividcode.happyride.dispatcherservice.api.events.MessageDestination;
import io.vividcode.happyride.dispatcherservice.dataaccess.DispatchRepository;
import io.vividcode.happyride.dispatcherservice.domain.Dispatch;
import io.vividcode.happyride.dispatcherservice.domain.DispatchDomainEventPublisher;
import io.vividcode.happyride.tripservice.api.events.AcceptTripDetails;
import io.vividcode.happyride.tripservice.api.events.TripDetails;
import io.vividcode.happyride.tripservice.api.events.TripDispatchedEvent;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.transaction.Transactional;
import lombok.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands.DistanceUnit;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoRadiusCommandArgs;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

@Service
public class DispatcherService {

  @Autowired
  RedisTemplate<String, String> redisTemplate;

  @Autowired
  MessageProducer messageProducer;

  @Autowired
  TaskScheduler taskScheduler;

  @Autowired
  DispatchRepository dispatchRepository;

  @Autowired
  DispatchDomainEventPublisher eventPublisher;

  private final Distance searchRadius = new Distance(10, DistanceUnit.KILOMETERS);
  private final String key = "available_drivers";
  private final String passenger = "__passenger";

  public void addAvailableDriver(DriverLocation location) {
    redisTemplate.opsForGeo()
        .add(key, new Point(location.getLng().doubleValue(), location.getLat().doubleValue()),
            location.getDriverId());
  }

  public void removeAvailableDriver(String driverId) {
    redisTemplate.opsForGeo().remove(key, driverId);
  }

  public Set<AvailableDriver> findAvailableDrivers(double lng, double lat) {
    GeoResults<GeoLocation<String>> results = redisTemplate.opsForGeo()
        .radius(key, new Circle(new Point(lng, lat), searchRadius));
    if (results != null) {
      return results.getContent().stream().filter(Objects::nonNull)
          .map(result -> {
            GeoLocation<String> content = result.getContent();
            Point point = content.getPoint();
            return new AvailableDriver(content.getName(),
                BigDecimal.valueOf(point.getX()), BigDecimal.valueOf(point.getY()));
          })
          .collect(Collectors.toSet());
    }
    return Collections.emptySet();
  }

  @Transactional
  public void dispatchTrip(String tripId, TripDetails tripDetails) {
    Position startPos = tripDetails.getStartPos();
    Set<AvailableDriver> availableDrivers = findAvailableDrivers(startPos.getLng().doubleValue(), startPos.getLat().doubleValue()
        );
    ResultWithDomainEvents<Dispatch, DispatchDomainEvent> resultWithDomainEvents = Dispatch
        .createDispatch(tripId, tripDetails, availableDrivers);
    Dispatch dispatch = dispatchRepository.save(resultWithDomainEvents.result);
    eventPublisher.publish(dispatch, resultWithDomainEvents.events);
  }

  public void acceptTrip(AcceptTripDetails details) {

  }

  private void startTripAcceptanceCheck(String tripId, TripDetails tripDetails, Duration duration) {
    redisTemplate.opsForGeo()
        .add(keyForTripAcceptance(tripId),
            new Point(tripDetails.getStartPos().getLng().doubleValue(),
                tripDetails.getStartPos().getLat().doubleValue()), passenger);
    taskScheduler.schedule(new CheckTripAcceptanceTask(), Instant.from(LocalDate.now().plus(duration)));
  }

  private void addDriverToAcceptTrip(AcceptTripDetails details) {
    redisTemplate.opsForGeo()
        .add(keyForTripAcceptance(details.getTripId()),
            new Point(details.getLng().doubleValue(),
                details.getLat().doubleValue()), details.getDriverId());
  }

  public void findDriverToAcceptTrip(String tripId) {
    GeoResults<GeoLocation<String>> results = redisTemplate.opsForGeo()
        .radius(keyForTripAcceptance(tripId), passenger, searchRadius, GeoRadiusCommandArgs.newGeoRadiusArgs().sortAscending());

  }

  private String keyForTripAcceptance(String tripId) {
    return String.format("accept_trip_%s", tripId);
  }

  private String keyForTripState(String tripId) {
    return String.format("trip_state_%s", tripId);
  }

  private static class CheckTripAcceptanceTask implements  Runnable {

    @Override
    public void run() {

    }
  }
}

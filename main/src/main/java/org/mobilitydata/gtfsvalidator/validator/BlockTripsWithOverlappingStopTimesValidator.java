package org.mobilitydata.gtfsvalidator.validator;

import com.google.common.collect.Multimaps;
import org.mobilitydata.gtfsvalidator.annotation.GtfsValidator;
import org.mobilitydata.gtfsvalidator.annotation.Inject;
import org.mobilitydata.gtfsvalidator.notice.BlockTripsWithOverlappingStopTimesNotice;
import org.mobilitydata.gtfsvalidator.notice.NoticeContainer;
import org.mobilitydata.gtfsvalidator.table.GtfsCalendarDateTableContainer;
import org.mobilitydata.gtfsvalidator.table.GtfsCalendarTableContainer;
import org.mobilitydata.gtfsvalidator.table.GtfsStopTime;
import org.mobilitydata.gtfsvalidator.table.GtfsStopTimeTableContainer;
import org.mobilitydata.gtfsvalidator.table.GtfsTrip;
import org.mobilitydata.gtfsvalidator.table.GtfsTripTableContainer;
import org.mobilitydata.gtfsvalidator.type.GtfsDate;
import org.mobilitydata.gtfsvalidator.type.GtfsTime;
import org.mobilitydata.gtfsvalidator.util.CalendarUtil;
import org.mobilitydata.gtfsvalidator.util.ServiceIdIntersectionCache;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Checks to see if any trips with the same block id have overlapping stop times.
 * <p>
 * Both trips have to be operating on the same service day, as determined by comparing the service dates of the trips.
 * <p>
 * Generated notices:
 * <p><ul>
 * <li>{@link BlockTripsWithOverlappingStopTimesNotice}
 * </ul>
 */
@GtfsValidator
public class BlockTripsWithOverlappingStopTimesValidator extends FileValidator {
    @Inject
    GtfsTripTableContainer tripTable;
    @Inject
    GtfsStopTimeTableContainer stopTimeTable;
    @Inject
    GtfsCalendarTableContainer calendarTable;
    @Inject
    GtfsCalendarDateTableContainer calendarDateTable;

    @Override
    public void validate(NoticeContainer noticeContainer) {
        // If there are no trip or stop time entries, then we can stop right now.
        if (tripTable.entityCount() == 0 || stopTimeTable.entityCount() == 0) {
            return;
        }

        // Our strategy to find trips with overlapping stop times that are active on the same service date is to:
        // 1) iterate over groups of trips grouped by their block id;
        // 2) sort the trips within the block by stop times;
        // 3) iterate over each trip, looking for subsequent trips that
        //   (a) are active on the same service date and
        //   (b) have overlapping stop times.
        // Because the trips will be ordered by stop times, we shouldn't have to look too far down the list.
        final ServiceIdIntersectionCache serviceIdIntersectionCache = new ServiceIdIntersectionCache(
                CalendarUtil.servicePeriodToServiceDatesMap(
                        CalendarUtil.buildServicePeriodMap(calendarTable, calendarDateTable)));
        for (List<GtfsTrip> tripsInBlock : Multimaps.asMap(tripTable.byBlockIdMap()).values()) {
            // We don't care about trips without a block id.
            if (!tripsInBlock.get(0).hasBlockId()) {
                continue;
            }
            // We need a first arrival time and a last departure time for each trip in the block to properly judge trip
            // overlap.
            for (GtfsTripOverlap overlap : findOverlapIntervals(
                    constructOrderedTripIntervals(tripsInBlock), serviceIdIntersectionCache)) {
                final GtfsTrip tripA = overlap.getTripA();
                final GtfsTrip tripB = overlap.getTripB();
                noticeContainer.addNotice(
                    new BlockTripsWithOverlappingStopTimesNotice(
                        tripA.csvRowNumber(), tripA.tripId(), tripA.serviceId(),
                        tripB.csvRowNumber(), tripB.tripId(), tripB.serviceId(),
                        tripA.blockId(),
                        GtfsDate.fromLocalDate(overlap.intersection)));
            }
        }
    }

    /**
     * Constructs an ordered list of GtfsTripIntervals for the specified rows in trips.txt.
     * <p>
     * Each trip's time interval is constructed from the first and last stop time for a trip. If a particular trip has
     * no stop times or if the first or last stop time of the trip has no arrival and departure information, the trip is
     * not included in the interval resulting list.
     * <p>
     * Intervals are sorted by increasing first-arrival times, and then last-departure time.
     */
    private List<GtfsTripInterval> constructOrderedTripIntervals(List<GtfsTrip> tripsInBlock) {
        ArrayList<GtfsTripInterval> intervals = new ArrayList<>();
        intervals.ensureCapacity(tripsInBlock.size());
        for (GtfsTrip trip : tripsInBlock) {
            final List<GtfsStopTime> stopTimes = stopTimeTable.byTripId(trip.tripId());
            if (stopTimes.isEmpty()) {
                // Invalid trip without stop times is reported separately.
                continue;
            }
            GtfsStopTime firstStopTime = stopTimes.get(0);
            GtfsStopTime lastStopTime = stopTimes.get(stopTimes.size() - 1);
            if (!firstStopTime.hasArrivalTime() || !firstStopTime.hasDepartureTime() ||
                    !lastStopTime.hasArrivalTime() || !lastStopTime.hasDepartureTime()) {
                continue;
            }
            intervals.add(new GtfsTripInterval(
                    trip,
                    firstStopTime.arrivalTime(), firstStopTime.departureTime(),
                    lastStopTime.arrivalTime(), lastStopTime.departureTime()));
        }
        // Sort the trips by their first arrival and last departure times.
        Collections.sort(intervals,
                Comparator.comparing(GtfsTripInterval::getFirstArrival)
                        .thenComparing(GtfsTripInterval::getLastDeparture));
        return intervals;
    }

    /**
     * Iterates over each trip intervals, looking for subsequent trips that:
     * (a) are active on the same service date and
     * (b) have overlapping stop times.
     */
    private List<GtfsTripOverlap> findOverlapIntervals(
            List<GtfsTripInterval> intervals,
            ServiceIdIntersectionCache serviceIdIntersectionCache) {
        List<GtfsTripOverlap> overlaps = new ArrayList<>();
        // Iterate over each trip, looking for subsequent trips that have
        // overlapping time ranges.
        for (int i = 0; i < intervals.size(); ++i) {
            final GtfsTripInterval interval = intervals.get(i);
            for (int j = i + 1; j < intervals.size(); ++j) {
                final GtfsTripInterval nextInterval = intervals.get(j);

                // We can stop searching for overlapping intervals if there is
                // no more overlap.
                if (interval.getLastDeparture().compareTo(nextInterval.getFirstArrival()) <= 0) {
                    break;
                }
                // We technically allow two trip intervals to have overlapping stop
                // time intervals if the arrival/departure pair of the last stop in the
                // previous trip is exactly the same as the arrival/departure pair of
                // the first stop in the next trip.  Many agencies model a block
                // transfer between two trips by basically replicating the
                // stop_times.txt entry for the two trips, creating a small overlap.
                if (interval.getLastArrival().equals(nextInterval.getFirstArrival()) &&
                        interval.getLastDeparture().equals(nextInterval.getFirstDeparture())) {
                    continue;
                }
                final Optional<LocalDate> intersection = serviceIdIntersectionCache.findIntersectingDate(
                        interval.getTrip().serviceId(), nextInterval.getTrip().serviceId());
                if (intersection.isPresent()) {
                    overlaps.add(new GtfsTripOverlap(
                            interval.getTrip(), nextInterval.getTrip(), intersection.get()));
                }
            }
        }
        return overlaps;
    }

    /**
     * Captures the time interval spanned by the first and last stop time of a particular GTFS trip.
     */
    private static class GtfsTripInterval {
        private final GtfsTrip trip;
        private final GtfsTime firstArrival;
        private final GtfsTime firstDeparture;
        private final GtfsTime lastArrival;
        private final GtfsTime lastDeparture;

        public GtfsTripInterval(GtfsTrip trip, GtfsTime firstArrival,
                                GtfsTime firstDeparture, GtfsTime lastArrival,
                                GtfsTime lastDeparture) {
            this.trip = trip;
            this.firstArrival = firstArrival;
            this.firstDeparture = firstDeparture;
            this.lastArrival = lastArrival;
            this.lastDeparture = lastDeparture;
        }

        public GtfsTrip getTrip() {
            return trip;
        }

        public GtfsTime getFirstArrival() {
            return firstArrival;
        }

        public GtfsTime getFirstDeparture() {
            return firstDeparture;
        }

        public GtfsTime getLastArrival() {
            return lastArrival;
        }

        public GtfsTime getLastDeparture() {
            return lastDeparture;
        }
    }

    /**
     * Captures a trip pair overlapped by time, stores the row numbers of the
     * overlapped trips and the first intersection.
     */
    private static class GtfsTripOverlap {
        private final GtfsTrip tripA;
        private final GtfsTrip tripB;
        private final LocalDate intersection;

        public GtfsTripOverlap(GtfsTrip tripA, GtfsTrip tripB,
                               LocalDate intersection) {
            this.tripA = tripA;
            this.tripB = tripB;
            this.intersection = intersection;
        }

        public GtfsTrip getTripA() {
            return tripA;
        }

        public GtfsTrip getTripB() {
            return tripB;
        }

        public LocalDate getIntersection() {
            return intersection;
        }
    }
}

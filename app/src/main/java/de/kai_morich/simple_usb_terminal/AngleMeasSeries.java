package de.kai_morich.simple_usb_terminal;

import java.util.LinkedList;

public class AngleMeasSeries {
    private LinkedList<Double> measurements;
    private int maxCapacity;

    public AngleMeasSeries(int maxCapacity) {
        this.measurements = new LinkedList<>();
        this.maxCapacity = maxCapacity;
    }

    public void addMeasurement(double measurement) {
        measurements.addLast(measurement);
        double average = measurements.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
//        System.out.println("added measurement: " + measurement + "average: " + average);

        // Remove the oldest measurement if the series exceeds the specified capacity
        while (measurements.size() > maxCapacity) {
            measurements.removeFirst();
        }
    }

    public Boolean addMeasurementFiltered(double measurement) {
        Boolean isMoving = false;
        //just add values regardless until the buffer is full
        if (measurements.size() < maxCapacity) {
            addMeasurement(measurement);

            //once buffer is full, try to detect any outliers
            // (if error rate is too high while buffer is being filled for the first time,
            // we'll be in trouble, but that should be detectable by the user while setting the
            // phone up, so they should be able to just reboot
//            if (measurements.size() == maxCapacity) {
//                detectAndDiscardOutlier();
//            }
        } else {

            // Calculate the average of current measurements
            double average = 0;
            //condition here is to check
            average = measurements.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);


            // Check if the new measurement is within a certain threshold of the average
            double threshold = 15.0; // Adjust the threshold as needed
            if (Math.abs(measurement - average) <= threshold) {
                addMeasurement(measurement);
                if (Math.abs(measurement - average) >= 3.0) {
                    isMoving = true;
                }
            } else {
                System.out.println("Discarded measurement: " + measurement + " (deviates too much from the average: " + average);
            }
        }
        return isMoving;
    }

    private void detectAndDiscardOutlier() {
        // Calculate the average of current measurements
        double average = measurements.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        // Calculate the standard deviation
        double sumSquaredDiff = measurements.stream().mapToDouble(val -> Math.pow(val - average, 2)).sum();
        double standardDeviation = Math.sqrt(sumSquaredDiff / measurements.size());

        // Set a threshold for outliers (e.g., Z-Score > 3.0)
        double zScoreThreshold = 3.0;

        // Identify and discard outliers
        measurements.removeIf(value -> Math.abs(value - average) / standardDeviation > zScoreThreshold);
    }


    public LinkedList<Double> getMeasurements() {
        return measurements;
    }
}

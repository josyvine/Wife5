package com.inout.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.inout.app.models.AttendanceRecord;
import com.inout.app.utils.TimeUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Utility to generate and share professional attendance reports.
 * UPDATED: Handles 14-column layout with strict logic for Late Starts, Resume, and Paid Medical Leave.
 */
public class CsvExportHelper {

    private static final String TAG = "CsvExportHelper";

    /**
     * Converts the full month list into a CSV-formatted string and opens the share menu.
     */
    public static void exportAttendanceToCsv(Context context, List<AttendanceRecord> records, String fileName) {
        
        // 1. Create the CSV Header Row (14 Columns)
        StringBuilder csvData = new StringBuilder();
        csvData.append("Date,Day,CheckIn,TransitRoute,CheckOut,AssignedShift,TotalHours,Overtime,Location,DistanceMeters,FingerprintVerified,GPSVerified,Status,Remarks\n");

        // 2. Loop through all records and format rows
        for (AttendanceRecord record : records) {
            String date = record.getDate();
            String day = record.getDayOfWeek();
            String inTime = (record.getCheckInTime() != null) ? record.getCheckInTime() : "--";
            String transit = record.getTransitSummary();
            String outTime = (record.getCheckOutTime() != null) ? record.getCheckOutTime() : "--";
            String shiftInfo = (record.getAssignedShift() != null) ? record.getAssignedShift() : "--";
            String overtime = (record.getOvertimeHours() != null) ? record.getOvertimeHours() : "--";
            String location = (record.getLocationName() != null) ? record.getLocationName() : "N/A";
            String distance = (record.getCheckInTime() != null) ? String.valueOf(Math.round(record.getDistanceMeters())) : "--";
            
            String finger = record.isFingerprintVerified() ? "YES" : "NO";
            String gps = record.isGpsVerified() ? "YES" : "NO";
            
            // LOGIC CONSTANTS
            String status = record.getStatus();
            String hours = (record.getTotalHours() != null) ? record.getTotalHours() : "0h 00m";
            String remarks = (record.getRemarks() != null) ? record.getRemarks() : "";
            String shiftDuration = calculateShiftDuration(shiftInfo);

            // SCENARIO 1: Emergency Leave (Not Resumed)
            if (record.getEmergencyLeaveTime() != null && record.getCheckOutTime() == null) {
                status = "Absent";
                hours = TimeUtils.calculateDuration(record.getCheckInTime(), record.getEmergencyLeaveTime());
            }

            // SCENARIO 2: Resumed Work / Late Start (Check-Out exists)
            if (record.getCheckOutTime() != null) {
                
                // Case: Paid Medical Leave (Give full shift credit)
                if ("paid".equals(record.getMedicalLeaveType())) {
                    hours = shiftDuration;
                } 
                // Case: Late Start / Resume (Record discrepancy in remarks)
                else if (record.isResumeRequested()) {
                    String lateDetail = "Late on duty. Worked " + hours + " of assigned " + shiftDuration;
                    if (remarks.isEmpty()) {
                        remarks = lateDetail;
                    } else if (!remarks.contains("Late")) {
                        remarks = remarks + " | " + lateDetail;
                    }
                }
            }

            // SANITIZE: Remove raw system line breaks inside fields to prevent CSV row-splitting corruption
            String sanitizedTransit = transit.replace("\r", "").replace("\n", " ");
            String sanitizedLocation = location.replace("\r", "").replace("\n", " ");
            String sanitizedRemarks = remarks.replace("\r", "").replace("\n", " ");

            // Append row to string (Wrap multi-word strings in quotes to handle commas)
            csvData.append(date).append(",")
                    .append(day).append(",")
                    .append(inTime).append(",")
                    .append("\"").append(sanitizedTransit).append("\",")
                    .append(outTime).append(",")
                    .append(shiftInfo).append(",")
                    .append(hours).append(",")
                    .append(overtime).append(",")
                    .append("\"").append(sanitizedLocation).append("\",")
                    .append(distance).append(",")
                    .append(finger).append(",")
                    .append(gps).append(",")
                    .append(status).append(",")
                    .append("\"").append(sanitizedRemarks).append("\"\n");
        }

        // 3. Save and Share logic
        try {
            File folder = new File(context.getCacheDir(), "reports");
            if (!folder.exists()) folder.mkdirs();
            
            File file = new File(folder, fileName + ".csv");
            FileOutputStream outStream = new FileOutputStream(file);
            outStream.write(csvData.toString().getBytes());
            outStream.close();

            shareCsvFile(context, file);

        } catch (IOException e) {
            Log.e(TAG, "CSV Export failed", e);
            Toast.makeText(context, "Error generating report", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Helper to calculate the duration of a shift string like "02:25 PM - 02:30 PM"
     */
    private static String calculateShiftDuration(String shiftStr) {
        if (shiftStr == null || !shiftStr.contains("-")) return "0h 00m";
        try {
            String[] parts = shiftStr.split("-");
            if (parts.length == 2) {
                return TimeUtils.calculateDuration(parts[0].trim(), parts[1].trim());
            }
        } catch (Exception e) {
            Log.e(TAG, "Shift parse error", e);
        }
        return "0h 00m";
    }

    private static void shareCsvFile(Context context, File file) {
        Uri path = FileProvider.getUriForFile(context, "com.inout.app.fileprovider", file);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_SUBJECT, "Attendance Report");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_STREAM, path);
        context.startActivity(Intent.createChooser(intent, "Export Report via:"));
    }
}
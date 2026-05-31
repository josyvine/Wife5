package com.inout.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.bumptech.glide.Glide;
import com.inout.app.models.AttendanceRecord;
import com.inout.app.models.User;
import com.inout.app.utils.EncryptionHelper;
import com.inout.app.utils.TimeUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Utility to generate and share highly professional, formatted PDF attendance reports.
 * Employs Landscape A4 coordinate systems and dynamic multi-line cell calculations.
 */
public class PdfExportHelper {

    private static final String TAG = "PdfExportHelper";

    // Page layout coordinates (A4 Landscape: 1120 width x 792 height)
    private static final int PAGE_WIDTH = 1120;
    private static final int PAGE_HEIGHT = 792;
    private static final int MARGIN_LEFT = 40;
    private static final int MARGIN_RIGHT = 40;
    private static final int MARGIN_TOP = 40;
    private static final int MARGIN_BOTTOM = 40;

    // Defined column widths (Sum matches 1040 pixels printable area)
    private static final int[] COLUMN_WIDTHS = {
            60,  // Date
            60,  // Day
            50,  // Check-In
            150, // Transit Route (Intelligent dynamic wrap)
            50,  // Check-Out
            110, // Assigned Shift
            50,  // Total Hours
            50,  // Overtime
            110, // Location (Intelligent dynamic wrap)
            50,  // Distance
            40,  // Fingerprint Verified
            40,  // GPS Verified
            60,  // Status
            160  // Remarks (Intelligent dynamic wrap)
    };

    private static final String[] HEADERS = {
            "Date", "Day", "In", "Transit Route", "Out", "Assigned Shift", "Total", "O.T.", "Location", "Dist.", "Fin", "GPS", "Status", "Remarks"
    };

    /**
     * Spawns a background thread to generate and share the PDF document.
     */
    public static void exportAttendanceToPdf(Context context, User employee, List<AttendanceRecord> records, String fileName) {
        new Thread(() -> {
            Bitmap profileBitmap = null;

            // 1. Fetch user photo if a valid URL exists
            if (employee.getPhotoUrl() != null && !employee.getPhotoUrl().isEmpty()) {
                try {
                    profileBitmap = Glide.with(context)
                            .asBitmap()
                            .load(employee.getPhotoUrl())
                            .circleCrop()
                            .submit(90, 90) // Scaled exactly for the CV Header container
                            .get();
                } catch (Exception e) {
                    Log.e(TAG, "Profile image download failed", e);
                }
            }

            // 2. Pass to generator on the background thread
            generatePdfFile(context, employee, records, fileName, profileBitmap);
        }).start();
    }

    private static void generatePdfFile(Context context, User employee, List<AttendanceRecord> records, String fileName, Bitmap profileBitmap) {
        PdfDocument document = new PdfDocument();
        int pageIndex = 1;

        // Initialize reusable painting configurations
        Paint paint = new Paint();
        TextPaint textPaint = new TextPaint();
        textPaint.setAntiAlias(true);

        // Create the first page
        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageIndex).create();
        PdfDocument.Page page = document.startPage(pageInfo);
        Canvas canvas = page.getCanvas();

        // 3. Draw CV Header (Only rendered on the first page)
        drawCvHeader(context, canvas, employee, profileBitmap, paint, textPaint);

        // Vertical tracker starts below the CV Header layout
        int currentY = 180;

        // 4. Draw Table Header Row
        drawTableHeader(canvas, currentY, paint, textPaint);
        currentY += 30; // Move below the header row bounding box

        // 5. Populate Data Rows dynamically
        for (AttendanceRecord record : records) {
            String date = record.getDate();
            String day = record.getDayOfWeek();
            String inTime = (record.getCheckInTime() != null) ? record.getCheckInTime() : "--";
            String transit = record.getTransitSummary();
            String outTime = (record.getCheckOutTime() != null) ? record.getCheckOutTime() : "--";
            String shiftInfo = (record.getAssignedShift() != null) ? record.getAssignedShift() : "--";
            String overtime = (record.getOvertimeHours() != null) ? record.getOvertimeHours() : "--";
            String location = (record.getLocationName() != null) ? record.getLocationName() : "N/A";
            String distance = (record.getCheckInTime() != null) ? String.valueOf(Math.round(record.getDistanceMeters())) + "m" : "--";
            String finger = record.isFingerprintVerified() ? "YES" : "NO";
            String gps = record.isGpsVerified() ? "YES" : "NO";
            String status = record.getStatus();
            String hours = (record.getTotalHours() != null) ? record.getTotalHours() : "0h 00m";
            String remarks = (record.getRemarks() != null) ? record.getRemarks() : "";

            // SCENARIO 1: Emergency Leave (Not Resumed)
            if (record.getEmergencyLeaveTime() != null && record.getCheckOutTime() == null) {
                status = "Absent";
                hours = TimeUtils.calculateDuration(record.getCheckInTime(), record.getEmergencyLeaveTime());
            }

            // SCENARIO 2: Resumed Work / Late Start (Check-Out exists)
            String shiftDuration = calculateShiftDuration(shiftInfo);
            if (record.getCheckOutTime() != null) {
                if ("paid".equals(record.getMedicalLeaveType())) {
                    hours = shiftDuration;
                } else if (record.isResumeRequested()) {
                    String lateDetail = "Late on duty. Worked " + hours + " of assigned " + shiftDuration;
                    if (remarks.isEmpty()) {
                        remarks = lateDetail;
                    } else if (!remarks.contains("Late")) {
                        remarks = remarks + " | " + lateDetail;
                    }
                }
            }

            // Prepare array of row cell texts
            String[] rowData = {
                    date, day, inTime, transit, outTime, shiftInfo, hours, overtime, location, distance, finger, gps, status, remarks
            };

            // Prepare layout measurements for every single cell in this row to calculate height
            StaticLayout[] layouts = new StaticLayout[14];
            int rowHeight = 25; // Minimum standard cell height

            for (int i = 0; i < 14; i++) {
                textPaint.setTextSize(8f);
                textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
                textPaint.setColor(Color.BLACK);

                // Set different status colors to reflect app UI [2]
                if (i == 12) {
                    if ("Present".equals(rowData[i])) textPaint.setColor(Color.parseColor("#00796B"));
                    else if ("Partial".equals(rowData[i])) textPaint.setColor(Color.parseColor("#F57C00"));
                    else textPaint.setColor(Color.parseColor("#D32F2F"));
                }

                // Compile multiline dynamic layout
                layouts[i] = new StaticLayout(rowData[i], textPaint, COLUMN_WIDTHS[i] - 6,
                        Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false);

                // Dynamically expand height based on text wrapping requirements [2]
                if (layouts[i].getHeight() + 8 > rowHeight) {
                    rowHeight = layouts[i].getHeight() + 8;
                }
            }

            // If drawing this row breaches bottom page bounds, finalize current page and create a new page [2]
            if (currentY + rowHeight > PAGE_HEIGHT - MARGIN_BOTTOM) {
                document.finishPage(page);

                pageIndex++;
                pageInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageIndex).create();
                page = document.startPage(pageInfo);
                canvas = page.getCanvas();

                // Draw Table Header again on the newly spawned page [2]
                currentY = MARGIN_TOP;
                drawTableHeader(canvas, currentY, paint, textPaint);
                currentY += 30;
            }

            // Draw grid line boundaries and cell contents
            drawDataRow(canvas, currentY, rowHeight, layouts, paint);
            currentY += rowHeight;
        }

        // Finalize last active page
        document.finishPage(page);

        // 6. Save document output
        try {
            File folder = new File(context.getCacheDir(), "reports");
            if (!folder.exists()) folder.mkdirs();

            File file = new File(folder, fileName + ".pdf");
            FileOutputStream outStream = new FileOutputStream(file);
            document.writeTo(outStream);
            document.close();
            outStream.close();

            // Trigger shares on main thread [2]
            new Handler(Looper.getMainLooper()).post(() -> sharePdfFile(context, file));

        } catch (IOException e) {
            Log.e(TAG, "PDF write failed", e);
            document.close();
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(context, "Failed to compile PDF report", Toast.LENGTH_SHORT).show()
            );
        }
    }

    private static void drawCvHeader(Context context, Canvas canvas, User employee, Bitmap profileBitmap, Paint paint, TextPaint textPaint) {
        // Draw Light Gray Card Background
        paint.setColor(Color.parseColor("#F5F5F5"));
        canvas.drawRect(MARGIN_LEFT, MARGIN_TOP, PAGE_WIDTH - MARGIN_RIGHT, 150, paint);

        // Draw Circular Profile Photo container [2]
        int photoX = MARGIN_LEFT + 20;
        int photoY = MARGIN_TOP + 15;
        if (profileBitmap != null) {
            canvas.drawBitmap(profileBitmap, photoX, photoY, paint);
        } else {
            // Draw clean circle fallback if photo is missing or offline
            paint.setColor(Color.parseColor("#00796B"));
            canvas.drawCircle(photoX + 45, photoY + 45, 45, paint);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(24f);
            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(employee.getName().substring(0, 1).toUpperCase(), photoX + 45, photoY + 53, textPaint);
        }

        // Draw Employee Details Text Stack
        int textStartX = photoX + 110;
        textPaint.setTextAlign(Paint.Align.LEFT);

        // Employee Name
        textPaint.setColor(Color.BLACK);
        textPaint.setTextSize(16f);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        canvas.drawText(employee.getName(), textStartX, MARGIN_TOP + 30, textPaint);

        // ID Details
        textPaint.setColor(Color.parseColor("#616161"));
        textPaint.setTextSize(11f);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        canvas.drawText("ID: " + employee.getEmployeeId(), textStartX, MARGIN_TOP + 48, textPaint);

        // Contact Number
        canvas.drawText("Phone: " + employee.getPhone(), textStartX, MARGIN_TOP + 64, textPaint);

        // Assigned Company Name
        textPaint.setColor(Color.parseColor("#00796B"));
        textPaint.setTextSize(11f);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        canvas.drawText(EncryptionHelper.getInstance(context).getCompanyName(), textStartX, MARGIN_TOP + 80, textPaint);

        // Report Month Box [2]
        paint.setColor(Color.parseColor("#E0F2F1"));
        RectF rectBox = new RectF(photoX, MARGIN_TOP + 115, photoX + 140, MARGIN_TOP + 138);
        canvas.drawRoundRect(rectBox, 6f, 6f, paint);

        textPaint.setColor(Color.BLACK);
        textPaint.setTextSize(10f);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        Calendar cal = Calendar.getInstance();
        String currentMonthYear = new SimpleDateFormat("MMMM yyyy", Locale.US).format(cal.getTime());
        canvas.drawText(currentMonthYear, photoX + 12, MARGIN_TOP + 131, textPaint);
    }

    private static void drawTableHeader(Canvas canvas, int y, Paint paint, TextPaint textPaint) {
        // Table Header fill
        paint.setColor(Color.parseColor("#00796B"));
        canvas.drawRect(MARGIN_LEFT, y, PAGE_WIDTH - MARGIN_RIGHT, y + 30, paint);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(8.5f);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        int startX = MARGIN_LEFT;
        for (int i = 0; i < 14; i++) {
            // Center the text mathematically in the column width bounds
            Rect textBounds = new Rect();
            textPaint.getTextBounds(HEADERS[i], 0, HEADERS[i].length(), textBounds);
            int posX = startX + (COLUMN_WIDTHS[i] - textBounds.width()) / 2;

            canvas.drawText(HEADERS[i], posX, y + 18, textPaint);
            startX += COLUMN_WIDTHS[i];
        }

        // Draw bottom boundary line
        paint.setColor(Color.parseColor("#BDBDBD"));
        paint.setStrokeWidth(1.0f);
        canvas.drawLine(MARGIN_LEFT, y + 30, PAGE_WIDTH - MARGIN_RIGHT, y + 30, paint);
    }

    private static void drawDataRow(Canvas canvas, int y, int height, StaticLayout[] layouts, Paint paint) {
        // Grid vertical stroke specs
        paint.setColor(Color.parseColor("#E0E0E0"));
        paint.setStrokeWidth(1.0f);
        paint.setStyle(Paint.Style.STROKE);

        // Draw horizontal bottom line of row cell
        canvas.drawLine(MARGIN_LEFT, y + height, PAGE_WIDTH - MARGIN_RIGHT, y + height, paint);

        int startX = MARGIN_LEFT;
        for (int i = 0; i < 14; i++) {
            // Draw left divider boundary
            canvas.drawLine(startX, y, startX, y + height, paint);

            // Print the dynamic static wrapping layout
            canvas.save();
            canvas.translate(startX + 3, y + 4); // Added 4px vertical padding
            layouts[i].draw(canvas);
            canvas.restore();

            startX += COLUMN_WIDTHS[i];
        }

        // Draw absolute right bounding divider line
        canvas.drawLine(PAGE_WIDTH - MARGIN_RIGHT, y, PAGE_WIDTH - MARGIN_RIGHT, y + height, paint);
    }

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

    private static void sharePdfFile(Context context, File file) {
        Uri path = FileProvider.getUriForFile(context, "com.inout.app.fileprovider", file);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("application/pdf");
        intent.putExtra(Intent.EXTRA_SUBJECT, "Attendance Report PDF");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_STREAM, path);
        context.startActivity(Intent.createChooser(intent, "Export Report via:"));
    }
}
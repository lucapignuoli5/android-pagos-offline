package com.example.prototipopagosoffline.utils

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.prototipopagosoffline.database.TransactionHistory
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReceiptUtils {

    fun generateReceiptBitmap(transaction: TransactionHistory): Bitmap {
        val width = 600
        val height = 800
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()

        // Background
        canvas.drawColor(Color.WHITE)

        // Header
        paint.color = Color.BLACK
        paint.textSize = 40f
        paint.isFakeBoldText = true
        canvas.drawText("COMPROBANTE DE PAGO", 100f, 100f, paint)

        // Divider
        paint.strokeWidth = 2f
        canvas.drawLine(50f, 130f, 550f, 130f, paint)

        // Content
        paint.isFakeBoldText = false
        paint.textSize = 30f
        
        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        val dateStr = dateFormat.format(Date(transaction.timestamp.toLong() * 1000))
        
        canvas.drawText("Monto: $${Math.abs(transaction.monto)}", 70f, 200f, paint)
        canvas.drawText("Fecha: $dateStr", 70f, 260f, paint)
        canvas.drawText("ID Transacción: ${transaction.idTransaccion.takeLast(12)}", 70f, 320f, paint)
        canvas.drawText("Estado: PENDIENTE DE SINCRONIZACIÓN", 70f, 380f, paint)

        // Footer
        paint.textSize = 25f
        paint.color = Color.GRAY
        canvas.drawText("Gracias por usar Pagos Offline", 150f, 750f, paint)

        return bitmap
    }

    fun shareReceipt(context: Context, transaction: TransactionHistory) {
        val bitmap = generateReceiptBitmap(transaction)
        val imagesFolder = File(context.cacheDir, "images")
        if (!imagesFolder.exists()) imagesFolder.mkdirs()
        
        val file = File(imagesFolder, "receipt_${transaction.idTransaccion}.png")
        val stream = FileOutputStream(file)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        stream.flush()
        stream.close()

        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Compartir Recibo"))
    }
}

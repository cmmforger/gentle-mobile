package com.gentlemanprogramming.gentlemobile.ui.splash

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gentlemanprogramming.gentlemobile.R
import com.gentlemanprogramming.gentlemobile.ui.theme.LocalPalette

/**
 * The splash from the design (S21, board 01): the app's own mark (the bundled
 * logo asset) on the brand background with the wordmark and a thin progress
 * line. It is never a fake delay: the caller shows it only while the first
 * screen's real cold-start work (the identity key) is in flight, and hides it
 * the moment that screen is ready — including on failure, where the error is
 * shown instead of a spinner that hides it.
 */
@Composable
fun SplashScreen() {
    val p = LocalPalette.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(p.bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.splash_logo),
                contentDescription = "Gentle Mobile",
                contentScale = ContentScale.Fit,
                modifier = Modifier.width(96.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Gentle Mobile",
                color = p.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(24.dp))
            // The thin progress line: quiet, accent, and honest — it lives
            // exactly as long as the cold-start work it stands for.
            LinearProgressIndicator(
                modifier = Modifier
                    .width(120.dp)
                    .height(2.dp),
                color = p.accent,
                trackColor = p.surfaceRaised,
            )
        }
    }
}

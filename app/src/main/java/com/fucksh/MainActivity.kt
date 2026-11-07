package com.fucksh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fucksh.ui.FuckshApp
import com.fucksh.ui.theme.FuckshTheme
import com.fucksh.viewmodel.BlockerViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as FuckshApplication).container
        setContent {
            FuckshTheme {
                val viewModel: BlockerViewModel = viewModel(
                    factory = BlockerViewModel.Factory(application, container),
                )
                FuckshApp(viewModel)
            }
        }
    }
}

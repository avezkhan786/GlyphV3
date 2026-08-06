package com.glyph.glyph_v3.ui.auth.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glyph.glyph_v3.ui.auth.data.CountryData
import com.glyph.glyph_v3.ui.auth.models.Country
import com.glyph.glyph_v3.ui.theme.glyphTheme

/**
 * Material 3 bottom sheet country picker with search.
 *
 * Displays a scrollable list of countries with flag emoji, name, and calling code.
 * Includes a search field at the top that filters by country name or calling code.
 *
 * @param selectedCountry The currently selected country (highlighted with a checkmark).
 * @param onCountrySelected Called when the user taps a country row.
 * @param onDismiss Called when the sheet is dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountryPickerSheet(
    selectedCountry: Country,
    onCountrySelected: (Country) -> Unit,
    onDismiss: () -> Unit
) {
    val theme = glyphTheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    // Filter countries by search
    val filteredCountries = remember(searchQuery) {
        if (searchQuery.isBlank()) {
            CountryData.ALL_COUNTRIES
        } else {
            val q = searchQuery.trim().lowercase()
            CountryData.ALL_COUNTRIES.filter { country ->
                country.name.lowercase().contains(q) ||
                    country.callingCode.contains(q) ||
                    country.iso2.lowercase() == q
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = theme.backgroundElevated,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { /* visible drag handle */ }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            // Title
            Text(
                text = "Select Country",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = theme.textPrimary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Search field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = {
                    Text(
                        "Search by country name or code",
                        color = theme.textPlaceholder
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Search",
                        tint = theme.iconSecondary
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = theme.borderFocus,
                    unfocusedBorderColor = theme.borderInput,
                    focusedContainerColor = theme.surfaceInput,
                    unfocusedContainerColor = theme.surfaceInput,
                    cursorColor = theme.cursorColor
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Country list
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filteredCountries, key = { it.iso2 }) { country ->
                    val isSelected = country.iso2 == selectedCountry.iso2
                    CountryRow(
                        country = country,
                        isSelected = isSelected,
                        onClick = {
                            onCountrySelected(country)
                            onDismiss()
                        }
                    )
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }

        // Auto-focus search
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
    }
}

@Composable
private fun CountryRow(
    country: Country,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val theme = glyphTheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(
                if (isSelected) Modifier.background(
                    theme.actionPrimary.copy(alpha = 0.12f),
                    RoundedCornerShape(12.dp)
                ) else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Flag emoji
        Text(
            text = country.flagEmoji,
            fontSize = 24.sp
        )

        Spacer(modifier = Modifier.width(16.dp))

        // Country name
        Text(
            text = country.name,
            style = MaterialTheme.typography.bodyLarge,
            color = theme.textPrimary,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )

        // Calling code
        Text(
            text = "+${country.callingCode}",
            style = MaterialTheme.typography.bodyMedium,
            color = theme.textSecondary,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Checkmark if selected
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = theme.actionPrimary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

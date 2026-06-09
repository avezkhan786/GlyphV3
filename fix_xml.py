import re
with open('app/src/main/res/layout/activity_chat.xml', 'r', encoding='utf-8') as f:
    text = f.read()

insert = '''
        <!-- Interactive Map Button -->
        <androidx.compose.ui.platform.ComposeView
            android:id="@+id/interactiveMapButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            app:layout_constraintBottom_toTopOf="@id/layoutInput"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toEndOf="parent"
            android:layout_marginBottom="8dp"
            android:elevation="20dp"
            android:clipChildren="false"
            android:clipToPadding="false"
            android:visibility="gone" />
'''

text = re.sub(r'(\s*<androidx\.constraintlayout\.widget\.ConstraintLayout\s*android:id="@+id/layoutInput")', r'\n' + insert + r'\1', text)

with open('app/src/main/res/layout/activity_chat.xml', 'w', encoding='utf-8') as f:
    f.write(text)

package com.studyspot.plugin;

import androidx.core.content.FileProvider;

/** Own subclass so it never clashes with other plugins' FileProviders in the manifest. */
public class StudySpotFileProvider extends FileProvider {
}

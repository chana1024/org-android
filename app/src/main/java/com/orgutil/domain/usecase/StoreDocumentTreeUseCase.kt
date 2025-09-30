package com.orgutil.domain.usecase

import android.net.Uri
import com.orgutil.data.datasource.FileDataSource
import javax.inject.Inject

class StoreDocumentTreeUseCase @Inject constructor(
    private val fileDataSource: FileDataSource
) {
    operator fun invoke(uri: Uri) {
        fileDataSource.storeTreeUri(uri)
    }
}

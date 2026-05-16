package com.orgutil.domain.usecase

import android.net.Uri
import com.orgutil.data.datasource.FileDataSource
import javax.inject.Inject

class GetStoredDocumentTreeUseCase @Inject constructor(
    private val fileDataSource: FileDataSource
) {
    operator fun invoke(): Uri? {
        return fileDataSource.getStoredTreeUri()
    }
}

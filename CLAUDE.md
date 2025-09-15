# when your set text like this: 
```
Text(
    text = uiState.successMessage,
    style = MaterialTheme.typography.bodyMedium,
    color = Color(0xFF2E7D32),
    fontWeight = FontWeight.Medium
)
```
there maybe a error: Smart cast to 'String' is impossible, because 'uiState.error' is a complex expression.

correct code:
```     
uiState.successMessage?.let { message->
    Text(
        text = uiState.successMessage,
        style = MaterialTheme.typography.bodyMedium,
        color = Color(0xFF2E7D32),
        fontWeight = FontWeight.Medium
    )
}
```


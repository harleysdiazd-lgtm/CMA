package com.cma.comerciomundialalimentos.mercado

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import com.cma.comerciomundialalimentos.BDatos.ItemModeloDatosProducto
import com.cma.comerciomundialalimentos.BDatos.seleccionTipoProducto
import com.cma.comerciomundialalimentos.modelos.*
import com.cma.comerciomundialalimentos.modelos.coleccionFunciones.RegistrarProductoEnCatalogoPrivado
import com.cma.comerciomundialalimentos.modelos.coleccionFunciones.RegistrarProductoEnMercado
import com.cma.comerciomundialalimentos.modelos.coleccionFunciones.TextFieldAutocomplete
import com.cma.comerciomundialalimentos.modelos.coleccionFunciones.contienePublicidad
import com.cma.comerciomundialalimentos.modelos.items.ItemCartelInferior
import com.cma.comerciomundialalimentos.modelos.items.ItemCartelSuperior
import com.cma.comerciomundialalimentos.ui.theme.*
import com.cma.comerciomundialalimentos.usuarioActual
import android.location.Geocoder
import android.widget.Toast
import java.util.Locale
import kotlin.math.*
import kotlinx.coroutines.tasks.await
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import com.cma.comerciomundialalimentos.BDatos.EstadoTicket
import com.cma.comerciomundialalimentos.BDatos.PrioridadTicket
import com.cma.comerciomundialalimentos.BDatos.TicketServicio
import com.cma.comerciomundialalimentos.BDatos.TipoProblemaTicket
import com.cma.comerciomundialalimentos.BDatos.calcularDistanciaHaversine
import com.cma.comerciomundialalimentos.BaseDatosProductosDisponibles
import com.cma.comerciomundialalimentos.BaseDatosServicioTickets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.cma.comerciomundialalimentos.modelos.ResultadoBusquedaProducto
import com.cma.comerciomundialalimentos.R




@SuppressLint("SuspiciousIndentation")
@Composable
fun VenderScreen(navHostController: NavHostController, tipoUsuario: String) {
    // --- ESTADO DE BÚSQUEDA INTELIGENTE ---
    var queryBusqueda by remember { mutableStateOf("") }
    var sugerencias by remember { mutableStateOf<List<ResultadoBusquedaProducto>>(emptyList()) }

    // --- ESTADO DE SELECCIÓN (CORE) ---
    var categoriaSeleccionada by remember { mutableStateOf<CategoriaEstandar?>(null) }
    var productoSeleccionado by remember { mutableStateOf<ProductoEstandar?>(null) }
    var tipoSeleccionado by remember { mutableStateOf<TipoProductoEstandar?>(null) }
    var procesoSeleccionado by remember { mutableStateOf<ProcesoEstandar?>(null) }
    var esTransportePropio by remember { mutableStateOf(false) } 
    var esDescripcionIlegal by remember { mutableStateOf(false) } 

    // --- ESTADO DE DETALLES COMERCIALES ---
    var nombrePersonalizado by remember { mutableStateOf("") }
    var descripcion by remember { mutableStateOf("") }
    var cantidad by remember { mutableStateOf("") }
    var precio by remember { mutableStateOf("") }
    var empaque by remember { mutableStateOf("") }
    var fechaCosecha by remember { mutableStateOf("") }
    var certificaciones by remember { mutableStateOf("") }
    
    // --- ESTADO DE TRANSPORTE Y PRECIOS ---
    var precioTransporteKiloKilometro by remember { mutableStateOf("") }
    var promedioLocalTransporte by remember { mutableStateOf(0.0) }
    var confianzaTransporte by remember { mutableStateOf("") } // ALTA, MEDIA, BAJA, GLOBAL
    var flagAlertaTransporte by remember { mutableStateOf("") } // OK, ALERTA, FUERA
    var mensajeAvisoPrecioTransporte by remember { mutableStateOf("") }
    var colorAvisoPrecioTransporte by remember { mutableStateOf(GamaArmonicaTextoSecundario) }
    var responsableTransporte by remember { mutableStateOf("Productor") } // NUEVO ESTADO
    
    // --- ESTADO PARA IMAGEN DE REFERENCIA ---
    var resolvedImage by remember { mutableStateOf("") }
    
    val context = LocalContext.current
    
    // --- LÓGICA DE PROMEDIO Y DISTANCIA ---
    val codigoGenerado = remember(categoriaSeleccionada, tipoSeleccionado, productoSeleccionado, procesoSeleccionado) {
        val cat = categoriaSeleccionada?.id ?: "00"
        val prod = productoSeleccionado?.id ?: "00"
        val tipo = tipoSeleccionado?.id ?: "00"
        val proc = procesoSeleccionado?.id ?: "00"
        "$cat-$prod-$tipo-$proc"
    }

    // --- VERIFICACIÓN DE DUPLICADOS (CATÁLOGO PROPIO) ---
    val yaExisteEnCatalogo by remember(usuarioActual.value, codigoGenerado) {
        derivedStateOf<Boolean> {
            val misProductos = usuarioActual.value?.listaProductos ?: emptyList()
            android.util.Log.d("DUPLICADOS", "Buscando código: $codigoGenerado en lista de ${misProductos.size} productos.")
            
            // Buscar si existe algún producto activo/pausado con este mismo código estándar
            val existe = misProductos.any { 
                val iguales = it.codigoEstandar4 == codigoGenerado
                if (iguales) android.util.Log.d("DUPLICADOS", "Match encontrado: ${it.nombreProducto}")
                iguales
            }
            existe
        }
    }

    LaunchedEffect(codigoGenerado) {
        if (codigoGenerado == "00-00-00-00") return@LaunchedEffect
        
        try {
            // 1. Obtener Ubicación de Referencia (Lat/Lon)
            var latUser = usuarioActual.value?.latitudCache ?: 0.0
            var lonUser = usuarioActual.value?.longitudCache ?: 0.0
            
            // Fallback: Geocoding por Texto
            if (latUser == 0.0 || lonUser == 0.0) {
                val pais = usuarioActual.value?.pais ?: ""
                val depto = usuarioActual.value?.depto ?: ""
                val ciudad = usuarioActual.value?.ciudad ?: ""
                val direccion = "$ciudad, $depto, $pais"
                
                if (ciudad.isNotBlank()) {
                    try {
                        withContext(Dispatchers.IO) {
                            val geocoder = Geocoder(context, Locale.getDefault())
                            // Deprecated en API 33 pero funcional, o usar listener. Usamos forma simple bloqueante en IO.
                            @Suppress("DEPRECATION")
                            val addresses = geocoder.getFromLocationName(direccion, 1)
                            if (!addresses.isNullOrEmpty()) {
                                latUser = addresses[0].latitude
                                lonUser = addresses[0].longitude
                                // Opcional: Persistir en BD
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            
            // 2. Fetch Productos (Snapshot General - Optimizar en Producción)
            // Se asume BaseDatosProductosDisponibles accesible
            val snapshot = withContext(Dispatchers.IO) {
                 BaseDatosProductosDisponibles.get().await()
            }
            
            val todosLosProductos = snapshot.children.mapNotNull { it.getValue(ItemModeloDatosProducto::class.java) }
            
            // 3. Filtrado Jerárquico
            var filtrados = todosLosProductos.filter { it.codigoEstandar4 == codigoGenerado && it.precioTransporteKiloKilometro.toDoubleOrNull() ?: 0.0 > 0 }
            
            if (filtrados.isEmpty()) {
                // Mismo Producto (Ignorando Tipo específico y Proceso)
                // Nuevo formato: cat-prod-tipo-proc -> Indice 0 es cat, 1 es prod
                val catId = codigoGenerado.split("-").getOrNull(0) ?: ""
                val prodId = codigoGenerado.split("-").getOrNull(1) ?: ""
                
                filtrados = todosLosProductos.filter { 
                    val parts = it.codigoEstandar4.split("-")
                    parts.getOrNull(0) == catId && parts.getOrNull(1) == prodId && 
                    it.precioTransporteKiloKilometro.toDoubleOrNull() ?: 0.0 > 0
                }
            }
            
            if (filtrados.isEmpty()) {
                // Misma Categoría
                val catId = codigoGenerado.split("-").getOrNull(0) ?: ""
                filtrados = todosLosProductos.filter { 
                    it.codigoEstandar4.split("-").getOrNull(0) == catId &&
                    it.precioTransporteKiloKilometro.toDoubleOrNull() ?: 0.0 > 0 
                }
            }
            
            // 4. Distancia y Ordenamiento
            filtrados.forEach { prod ->
                // Asumiendo que el producto tiene ubicación (productor). 
                // Si no, usar origenCiudad/etc de prod para geocode (costoso). Usaremos 0 si no hay.
                // Nota: itemEtiquetaTransportes tiene 'ubicacionActualLatitud' pero ItemModeloDatosProducto no siempre la llena si no es logistica activa.
                // Usaremos lo que haya. ItemModeloDatosProducto tiene ubicacionActualLatitud? Sí, modificado en Plan? No, existe en el base.
                val dist = calcularDistanciaHaversine(latUser, lonUser, prod.ubicacionActualLatitud, prod.ubicacionActualLongitud)
                prod.distanciaDesdeElObservador = dist
            }
            
            // 5. Cálculo con Confianza y Promedio (Informe Técnico)
            val n = filtrados.size
            
            if (n >= 10) {
                confianzaTransporte = "ALTA"
                // Alta confianza: Promedio de los 10 más cercanos
                val top10 = filtrados.sortedBy { it.distanciaDesdeElObservador }.take(10)
                promedioLocalTransporte = top10.sumOf { it.precioTransporteKiloKilometro.toDoubleOrNull() ?: 0.0 } / top10.size
            } else if (n >= 5) {
                confianzaTransporte = "MEDIA"
                // Media confianza: Promedio de todos los disponibles (5 a 9)
                promedioLocalTransporte = filtrados.sumOf { it.precioTransporteKiloKilometro.toDoubleOrNull() ?: 0.0 } / n
            } else if (n > 0) {
                confianzaTransporte = "BAJA"
                // Baja confianza: Promedio de los pocos disponibles (< 5)
                promedioLocalTransporte = filtrados.sumOf { it.precioTransporteKiloKilometro.toDoubleOrNull() ?: 0.0 } / n
            } else {
                confianzaTransporte = "GLOBAL"
                // Sin mercado local: Usar referencia global (mock o 0.0 por ahora)
                promedioLocalTransporte = 0.0 // Aquí se podría conectar con precio_mercado_mundial si existiera
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    // --- LÓGICA AVISOS Y ALERTA (Informe Técnico) ---
    LaunchedEffect(precioTransporteKiloKilometro, promedioLocalTransporte, confianzaTransporte) {
        val precio = precioTransporteKiloKilometro.toDoubleOrNull() ?: 0.0
        
        if (precio > 0 && promedioLocalTransporte > 0) {
            val delta = precio - promedioLocalTransporte
            val ratio = delta / promedioLocalTransporte // Puede ser negativo o positivo
            
            // Umbrales absolutos para alerta (desviación hacia arriba o abajo)
            // Reporte: Ratio <= 0.10 (OK), 0.10 < Ratio <= 0.25 (ALERTA), > 0.25 (FUERA)
            // Asumimos simetría para "Fuera de mercado" tanto muy caro como muy barato sospechoso.
            val absRatio = kotlin.math.abs(ratio)
            
            if (absRatio <= 0.10) {
                flagAlertaTransporte = "OK"
                mensajeAvisoPrecioTransporte = "Precio Competitivo (Promedio: ${"%.2f".format(promedioLocalTransporte)})"
                colorAvisoPrecioTransporte = Color(0xFF4CAF50) // Verde
            } else if (absRatio <= 0.25) {
                flagAlertaTransporte = "ALERTA"
                val direccion = if (ratio > 0) "arriba" else "debajo"
                mensajeAvisoPrecioTransporte = "Alerta: 10%-25% por $direccion del promedio."
                colorAvisoPrecioTransporte = Color(0xFFFF9800) // Naranja
            } else {
                flagAlertaTransporte = "FUERA"
                val direccion = if (ratio > 0) "alto" else "bajo"
                mensajeAvisoPrecioTransporte = "Fuera de Mercado: Muy $direccion (>25% desviación)."
                colorAvisoPrecioTransporte = GamaArmonicaAcentoRojo
            }
            
            // Enriquecer mensaje con confianza
            val nMsg = when(confianzaTransporte) {
                "ALTA" -> "Confianza Alta (10+ vendedores)."
                "MEDIA" -> "Confianza Media (5-9 vendedores)."
                "BAJA" -> "Confianza Baja (<5 vendedores)."
                "GLOBAL" -> "Ref. Global (Sin local)."
                else -> ""
            }
            mensajeAvisoPrecioTransporte += "\n$nMsg"
            
        } else if (confianzaTransporte == "GLOBAL") {
             flagAlertaTransporte = "OK" // Asumimos OK si no hay referencia local contra que comparar
             mensajeAvisoPrecioTransporte = "Sin referencia local. Se usará precio global."
             colorAvisoPrecioTransporte = GamaArmonicaTextoSecundario
        } else {
            mensajeAvisoPrecioTransporte = ""
            flagAlertaTransporte = ""
        }
    }

    // --- AUTOMATIZACIÓN DE NOMBRE COMERCIAL ---
    LaunchedEffect(productoSeleccionado, tipoSeleccionado, procesoSeleccionado) {
        val prod = productoSeleccionado?.traducciones?.es ?: ""
        val tipo = tipoSeleccionado?.traducciones?.es ?: ""
        val proc = procesoSeleccionado?.traducciones?.es ?: ""
        
        if (prod.isNotBlank()) {
            val componentes = listOf(prod, tipo, proc).filter { it.isNotBlank() }
            nombrePersonalizado = componentes.joinToString(" ")
        }
    }

    // --- LÓGICA DE RESOLUCIÓN DE IMAGEN DE REFERENCIA (CON CACHÉ LOCAL) ---
    LaunchedEffect(codigoGenerado) {
        if (codigoGenerado == "00-00-00-00") {
            resolvedImage = ""
            return@LaunchedEffect
        }
        
        val parts = codigoGenerado.split("-")
        if (parts.size == 4) {
            val cat = parts[0]; val prod = parts[1]; val tipo = parts[2]; val proc = parts[3]
            
            // Replicar lógica general de DescripcionProductoScreen
            val pathWebp = if (proc == "00") "catalogo_productos/$cat/$prod/$tipo/${codigoGenerado}.webp"
                          else "catalogo_productos/$cat/$prod/$tipo/$proc/${codigoGenerado}.webp"
            
            var url = com.cma.comerciomundialalimentos.BDatos.StorageRepository.obtenerUrlImagen(pathWebp)
            
            if (url == null) {
                val pathJpg = if (proc == "00") "catalogo_productos/$cat/$prod/$tipo/${codigoGenerado}.jpg"
                              else "catalogo_productos/$cat/$prod/$tipo/$proc/${codigoGenerado}.jpg"
                url = com.cma.comerciomundialalimentos.BDatos.StorageRepository.obtenerUrlImagen(pathJpg)
            }
            
            if (url != null) {
                resolvedImage = url
                // Coil se encargará de guardarlo en image_cache para futuros usos
            } else {
                resolvedImage = ""
            }
        }
    }



    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GamaArmonicaFondo)
    ) {
        // ====== CARTEL SUPERIOR ======
        ItemCartelSuperior(
            titulo = stringResource(id = R.string.agregarproducto),
            subtitulo = stringResource(id = R.string.estandaresglobalescalidad),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            tituloTextAlign = TextAlign.Center
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }
            
            // 🔍 BARRA DE BÚSQUEDA INTELIGENTE
            item {
                val labelBusqueda = when {
                    productoSeleccionado != null -> stringResource(id = R.string.buscarvariedadproceso)
                    categoriaSeleccionada != null -> stringResource(id = R.string.buscarproductoen, categoriaSeleccionada?.traducciones?.obtenerTraduccion() ?: "")
                    else -> stringResource(id = R.string.buscarproductoej)
                }
                
                Text(stringResource(id = R.string.buscadormaestro), color = GamaArmonicaPrimario, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                OutlinedTextField(
                    value = queryBusqueda,
                    onValueChange = {
                        queryBusqueda = it
                        sugerencias = com.cma.comerciomundialalimentos.modelos.ProductoSearchEngine.buscar(
                            query = it,
                            catId = categoriaSeleccionada?.id,
                            prodId = productoSeleccionado?.id
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text(labelBusqueda) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GamaArmonicaPrimario,
                        unfocusedBorderColor = GamaArmonicaDivisor,
                        focusedLabelColor = GamaArmonicaPrimario,
                        cursorColor = GamaArmonicaPrimario
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
                
                if (sugerencias.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = GamaArmonicaTarjeta),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column {
                            sugerencias.take(5).forEach { res ->
                                Text(
                                    text = "${res.textoCoincidente} (${res.categoria.traducciones.obtenerTraduccion()})",
                                    color = GamaArmonicaTextoPrincipal,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            categoriaSeleccionada = res.categoria
                                            productoSeleccionado = res.producto
                                            tipoSeleccionado = res.tipo
                                            procesoSeleccionado = res.proceso
                                            queryBusqueda = ""
                                            sugerencias = emptyList()
                                        }
                                        .padding(16.dp),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Divider(color = GamaArmonicaDivisor.copy(alpha = 0.5f), thickness = 0.5.dp)
                            }
                        }
                    }
                }
            }
            
            
            // 🖼️ IMAGEN DE REFERENCIA (PRE-VISUALIZACIÓN)
            if (resolvedImage.isNotEmpty() || productoSeleccionado != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        AsyncImage(
                            model = if (resolvedImage.isEmpty()) R.drawable.ic_launcher_foreground else resolvedImage,
                            contentDescription = "Imagen de Referencia",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            placeholder = painterResource(id = R.drawable.ic_launcher_foreground),
                            error = painterResource(id = R.drawable.ic_launcher_foreground)
                        )
                    }
                }
            }

            // (MOVIDO A SECCIÓN TRANSPORTE Y LOGÍSTICA)

            // 🔢 CÓDIGO ESTÁNDAR
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = GamaArmonicaPrimario),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(id = R.string.idestandar, codigoGenerado),
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 2.sp,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(16.dp).align(Alignment.CenterHorizontally)
                    )
                }
            }

            // Selectores Estandarizados (Estilo Autocomplete)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextFieldAutocomplete(
                        lista = seleccionTipoProducto.dbEstandarizada.map { it.traducciones.obtenerTraduccion() },
                        lugar = categoriaSeleccionada?.traducciones?.obtenerTraduccion() ?: "",
                        label = stringResource(id = R.string.categoriaprincipal),
                        onCampoCambia = { nombre ->
                            // Buscamos coincidencia en el idioma actual (traduccion) O en español (backend logic reference)
                            categoriaSeleccionada = seleccionTipoProducto.dbEstandarizada.find { 
                                it.traducciones.obtenerTraduccion() == nombre || it.traducciones.es == nombre 
                            }
                            productoSeleccionado = null
                            tipoSeleccionado = null
                            procesoSeleccionado = null
                        }
                    )

                    if (categoriaSeleccionada != null) {
                        TextFieldAutocomplete(
                            lista = categoriaSeleccionada?.productos?.map { it.traducciones.obtenerTraduccion() } ?: emptyList(),
                            lugar = productoSeleccionado?.traducciones?.obtenerTraduccion() ?: "",
                            label = stringResource(id = R.string.productoespecifico),
                            onCampoCambia = { nombre ->
                                productoSeleccionado = categoriaSeleccionada?.productos?.find { 
                                    it.traducciones.obtenerTraduccion() == nombre || it.traducciones.es == nombre 
                                }
                                tipoSeleccionado = null
                                procesoSeleccionado = null
                            }
                        )
                    }

                    if (productoSeleccionado != null) {
                        TextFieldAutocomplete(
                            lista = productoSeleccionado?.tipos?.map { it.traducciones.obtenerTraduccion() } ?: emptyList(),
                            lugar = tipoSeleccionado?.traducciones?.obtenerTraduccion() ?: "",
                            label = stringResource(id = R.string.variedadtipo),
                            onCampoCambia = { nombre ->
                                tipoSeleccionado = productoSeleccionado?.tipos?.find { 
                                    it.traducciones.obtenerTraduccion() == nombre || it.traducciones.es == nombre 
                                }
                                procesoSeleccionado = null
                            }
                        )
                    }

                    if (tipoSeleccionado != null && tipoSeleccionado?.procesosDisponibles?.isNotEmpty() == true) {
                        TextFieldAutocomplete(
                            lista = tipoSeleccionado?.procesosDisponibles?.map { it.traducciones.obtenerTraduccion() } ?: emptyList(),
                            lugar = procesoSeleccionado?.traducciones?.obtenerTraduccion() ?: "",
                            label = stringResource(id = R.string.estadoproceso),
                            onCampoCambia = { nombre ->
                                procesoSeleccionado = tipoSeleccionado?.procesosDisponibles?.find { 
                                    it.traducciones.obtenerTraduccion() == nombre || it.traducciones.es == nombre
                                }
                            }
                        )
                    }
                }
            }

            // Datos Comerciales Finales
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        stringResource(id = R.string.informacioncomercial), 
                        color = GamaArmonicaPrimario, 
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        modifier = Modifier.padding(top = 10.dp)
                    )

                    OutlinedTextField(
                        value = descripcion,
                        onValueChange = { 
                            descripcion = it
                            esDescripcionIlegal = contienePublicidad(it)
                        },
                        label = { Text(stringResource(id = R.string.descripcionopcional)) },
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                        isError = esDescripcionIlegal,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GamaArmonicaPrimario,
                            unfocusedBorderColor = GamaArmonicaDivisor,
                            errorBorderColor = GamaArmonicaAcentoRojo
                        ),
                        shape = RoundedCornerShape(12.dp),
                        supportingText = {
                            if (esDescripcionIlegal) {
                                Text(stringResource(id = R.string.evitedatoscontacto), color = GamaArmonicaAcentoRojo)
                            }
                        }
                    )

                    val esProductor = tipoUsuario == "Productor"
                    val esCentro = tipoUsuario == "CentroOperacional"
                    val esSimplificado = esProductor || esCentro

                    if (!esSimplificado) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = cantidad,
                                    onValueChange = { cantidad = it },
                                    label = { Text(stringResource(id = R.string.stockkg)) },
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = GamaArmonicaPrimario,
                                        unfocusedBorderColor = GamaArmonicaDivisor
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                OutlinedTextField(
                                    value = precio,
                                    onValueChange = { precio = it },
                                    label = { Text(stringResource(id = R.string.precioxkg)) },
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = GamaArmonicaPrimario,
                                        unfocusedBorderColor = GamaArmonicaDivisor
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }

                            OutlinedTextField(
                                value = empaque,
                                onValueChange = { empaque = it },
                                label = { Text(stringResource(id = R.string.tipoempaque)) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = GamaArmonicaPrimario,
                                    unfocusedBorderColor = GamaArmonicaDivisor
                                ),
                                shape = RoundedCornerShape(12.dp)
                            )

                            OutlinedTextField(
                                value = fechaCosecha,
                                onValueChange = { fechaCosecha = it },
                                label = { Text(stringResource(id = R.string.fechaestimadaentrega)) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = GamaArmonicaPrimario,
                                    unfocusedBorderColor = GamaArmonicaDivisor
                                ),
                                shape = RoundedCornerShape(12.dp)
                            )

                            OutlinedTextField(
                                value = certificaciones,
                                onValueChange = { certificaciones = it },
                                label = { Text(stringResource(id = R.string.certificacionescalidad)) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = GamaArmonicaPrimario,
                                    unfocusedBorderColor = GamaArmonicaDivisor
                                ),
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    


                    // --- AVISO DE DUPLICADO ---
                    if (yaExisteEnCatalogo) {
                         Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)), // Fondo naranja claro
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF9800))
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("⚠️", fontSize = 24.sp, modifier = Modifier.padding(end = 8.dp))
                                Text(
                                    stringResource(id = R.string.yaexisteencatalogo),
                                    color = Color(0xFFE65100),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))

                    // --- SECCIÓN: RESPONSABLE DEL TRANSPORTE (NUEVO) ---

                    Text(
                        stringResource(id = R.string.productonoensistema),
                        color = GamaArmonicaTextoSecundario,
                        fontSize = 12.sp,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    
                    Spacer(Modifier.height(20.dp))

                    // Verificación estricta de jerarquía
                    val esTipoRequerido = productoSeleccionado?.tipos?.isNotEmpty() == true
                    val esTipoValido = if (esTipoRequerido) tipoSeleccionado != null else true

                    val esProcesoRequerido = tipoSeleccionado?.procesosDisponibles?.isNotEmpty() == true
                    val esProcesoValido = if (esProcesoRequerido) procesoSeleccionado != null else true

                    val camposBaseListos = categoriaSeleccionada != null && 
                                           productoSeleccionado != null && 
                                           esTipoValido && 
                                           esProcesoValido
                    val camposComercialesListos = if (esSimplificado) true else cantidad.isNotBlank() && precio.isNotBlank()
                    val esPublicacionValida = camposBaseListos && camposComercialesListos && !esDescripcionIlegal

                    // Lógica dinámica del botón
                    val textoBoton = when {
                        camposBaseListos -> when {
                            esProductor -> stringResource(id = R.string.agregarcatalogprivado)
                            esCentro -> stringResource(id = R.string.agregarconsolidacion)
                            else -> stringResource(id = R.string.publicarmercado)
                        }
                        else -> stringResource(id = R.string.solicitarcreacionproducto)
                    }
                    
                    val colorBoton = when {
                        yaExisteEnCatalogo -> Color.Gray
                        camposBaseListos -> GamaArmonicaPrimario
                        else -> Color(0xFFFF9800) // Naranja
                    }
                    
                    var enviandoSolicitud by remember { mutableStateOf(false) }

                    Button(
                        onClick = {
                            if (yaExisteEnCatalogo) {
                                Toast.makeText(context, context.getString(R.string.yaexisteencatalogo), Toast.LENGTH_LONG).show()
                                return@Button
                            }

                            if (camposBaseListos) {
                                if (esPublicacionValida) {
                                    val itemFinal = ItemModeloDatosProducto(
                                        codigoProductor = usuarioActual.value?.codigo.toString(),
                                        nombreProducto = if (nombrePersonalizado.isNotBlank()) nombrePersonalizado else productoSeleccionado!!.traducciones.es,
                                        descripcion = descripcion,
                                        categoria = categoriaSeleccionada!!.traducciones.es,
                                        cantidadKg = if (esSimplificado) "0" else cantidad,
                                        cantidadKgInicial = if (esSimplificado) "0" else cantidad,
                                        cantidadKgObjetivo = if (esCentro) "0" else (if (esProductor) "0" else "0"),
                                        precioKg = if (esSimplificado) "0" else precio,
                                        empaque = empaque,
                                        fechaCosecha = fechaCosecha,
                                        certificaciones = certificaciones,
                                        codigoEstandar4 = codigoGenerado,
                                        origenPais = usuarioActual.value?.pais ?: "",
                                        origenDepto = usuarioActual.value?.depto ?: "",
                                        origenCiudad = usuarioActual.value?.ciudad ?: "",
                                        origenDireccion = usuarioActual.value?.direccion ?: "",
                                        estado = when {
                                            esProductor -> "Sin Stock"
                                            esCentro -> "rellenandoStock"
                                            else -> "Publicado"
                                        },
                                        esTransportePropio = false, // Se configura en Actualizar
                                        imagen = if (resolvedImage.isNotEmpty()) resolvedImage else (productoSeleccionado?.imagen ?: ""),
                                        precioTransporteKiloKilometro = "", // Se configura en Actualizar
                                        precioReferenciaTransporte = promedioLocalTransporte,
                                        confianzaMuestreoPrecioTransporte = confianzaTransporte,
                                        flagAlertaPrecioTransporte = flagAlertaTransporte,
                                        responsablePagoTransporte = "Productor" // Por defecto, se edita después
                                    )

                                    if (esProductor) {
                                        RegistrarProductoEnCatalogoPrivado(itemFinal, navHostController) { idNuevo ->
                                             // Redireccionar a la pantalla de ACTUALIZAR PRODUCTO
                                             val route = "editar_producto/$idNuevo"
                                             navHostController.navigate(route) {
                                                 // Cerramos la pantalla de creación para que "Atrás" no vuelva aquí
                                                 popUpTo("crearProducto/$tipoUsuario") { inclusive = true }
                                                 launchSingleTop = true
                                             }
                                        }
                                    } else {
                                        RegistrarProductoEnMercado(itemFinal, navHostController, tipoUsuario)
                                    }
                                } else {
                                    Toast.makeText(context, context.getString(R.string.completacamposobligatorios), Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                // --- FLUJO SOLICITUD DE CREACIÓN (Ticket) ---
                                // El usuario solicitó abrir la pantalla de soporte/ticket directamente
                                navHostController.navigate("crear_ticket")
                            }
                        },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colorBoton),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .padding(bottom = 12.dp),
                        shape = RoundedCornerShape(16.dp),
                        elevation = ButtonDefaults.buttonElevation(4.dp)
                    ) {
                        if (enviandoSolicitud) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                        } else {
                            Text(textoBoton, fontWeight = FontWeight.Black, color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
        
        ItemCartelInferior()
    }
}
// calcularDistanciaHaversine MOVIDO a crearProductoScreenApoyo.kt

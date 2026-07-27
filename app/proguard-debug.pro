# Regole aggiuntive per l'APK di debug compatto (-Pcybersensei.debug.compatto).
#
# L'APK di debug è anche il modo in cui l'app arriva a chi la prova, e senza R8 si porta
# dietro una sessantina di megabyte di codice mai eseguito. Qui però si toglie soltanto: i
# nomi restano tutti quelli veri.
#
# Il motivo è che quasi tutti i modi in cui R8 rompe un'applicazione passano dal
# rinominare — un enum salvato per nome in SQLite, una classe cercata per riflessione, uno
# stack trace illeggibile. Senza rinomina quella famiglia di guasti non esiste, e il file
# resta comunque una frazione di quello di prima. Il tempo di build in più è l'unico prezzo.
-dontobfuscate
